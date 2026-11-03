package com.jmread.core.scramble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图片还原/压缩的总处理预算（PERF-01）。
 *
 * 单图宽高上限只能保证「一张图不爆 Canvas」，不能约束并发：阅读、预取、
 * 两个下载同时进入还原时，每份任务都会分配源 Bitmap、目标 Bitmap 与工作缓冲，
 * 峰值可达单图预算的数倍。这里按字节做**跨入口共享**的在途预算：
 *  - 任何进入像素处理的调用都必须先申请预算，`finally` 释放；
 *  - 等待可取消，取消的等待者不会泄漏名额；
 *  - FIFO 授予，重任务不会因为小任务持续插入而永久饥饿；
 *  - 单任务估算超过总预算时直接拒绝，由调用方进一步降采样，绝不无限等待。
 *
 * 本类不依赖 Android API，便于 JVM 单测。设备档位由
 * [configureForDevice]（MemoryBudget.init）注入。
 */
object ImageProcessingBudget {

    /** 单任务估算超过总预算：调用方必须降采样或明确失败，不能排队等一个永远不会满足的配额。 */
    class ExceededException(val requestedBytes: Long, val totalBytes: Long) :
        IllegalStateException("图片处理预算不足：需要 $requestedBytes 字节，总预算 $totalBytes 字节")

    private class Waiter(val bytes: Long, val seq: Long) {
        @Volatile var granted = false
        val signal = CompletableDeferred<Unit>()
    }

    private val lock = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private var seq = 0L

    @Volatile private var totalBytes: Long = 128L * 1024 * 1024
    @Volatile private var usedBytes: Long = 0L

    /** 像素处理的并行度：低内存 1，其余 2。 */
    @Volatile private var parallelism: Int = 2

    @Volatile private var dispatcher: CoroutineDispatcher? = null

    val total: Long get() = totalBytes
    val inUse: Long get() = usedBytes

    fun configureForDevice(lowRam: Boolean, memoryClassMb: Int) {
        val total = when {
            lowRam || memoryClassMb <= 128 -> 64L * 1024 * 1024
            memoryClassMb <= 255 -> 96L * 1024 * 1024
            memoryClassMb <= 383 -> 160L * 1024 * 1024
            else -> 256L * 1024 * 1024
        }
        configure(total, if (lowRam || memoryClassMb <= 128) 1 else 2)
    }

    fun configure(totalBytes: Long, parallelism: Int = 2) {
        this.totalBytes = totalBytes.coerceAtLeast(1L)
        this.parallelism = parallelism.coerceIn(1, 4)
    }

    /** 处理阶段专用执行器：网络 IO 与像素计算分离，避免还原任务占满 IO 线程池。 */
    fun processingDispatcher(): CoroutineDispatcher {
        dispatcher?.let { return it }
        return synchronized(this) {
            dispatcher ?: run {
                val index = AtomicInteger()
                val pool = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1)) { r ->
                    Thread(r, "jm-img-process-${index.incrementAndGet()}").apply { isDaemon = true }
                }
                pool.asCoroutineDispatcher().also { dispatcher = it }
            }
        }
    }

    /**
     * 估算一次「源 Bitmap + 目标 Bitmap + 单行缓冲」的峰值。
     *
     * ARGB_8888 = 4 字节/像素，源/目标各一份；行缓冲用于条带搬运。
     * 不含调用方已持有的压缩输入字节（那部分在进入处理前就已分配）。
     */
    fun estimateBytes(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        val pixels = width.toLong() * height.toLong()
        // 极端尺寸不能因 Long 乘法溢出变成负数/小数，饱和到 Long.MAX_VALUE 交给预算拒绝
        val bitmaps = if (pixels > Long.MAX_VALUE / 8L) Long.MAX_VALUE else pixels * 8L
        val row = width.toLong() * 4L
        return if (bitmaps > Long.MAX_VALUE - row) Long.MAX_VALUE else bitmaps + row
    }

    /** 单任务允许的最大像素数（源/目标两份位图，行缓冲由最终估算兜底）。 */
    fun maxPixelsPerJob(): Long = (totalBytes / 8L).coerceAtLeast(1L)

    /**
     * 在预算内执行 [block]；等待可取消。
     * @throws ExceededException 单任务估算已超过总预算
     */
    suspend fun <T> withBudget(estimatedBytes: Long, block: suspend () -> T): T {
        if (estimatedBytes <= 0L) return block()
        if (estimatedBytes > totalBytes) throw ExceededException(estimatedBytes, totalBytes)
        acquire(estimatedBytes)
        try {
            return block()
        } finally {
            release(estimatedBytes)
        }
    }

    private suspend fun acquire(bytes: Long) {
        val waiter = Waiter(bytes, ++seq)
        var registered = false
        while (true) {
            val grantedNow = lock.withLock {
                if (!registered) {
                    // 无人排队且放得下：直接占用，避免无谓的队列往返
                    if (waiters.isEmpty() && usedBytes + bytes <= totalBytes) {
                        usedBytes += bytes
                        return@withLock true
                    }
                    waiters.addLast(waiter)
                    registered = true
                }
                false
            }
            if (grantedNow) return
            try {
                waiter.signal.await()
            } catch (e: CancellationException) {
                var releaseGranted = false
                lock.withLock {
                    if (!waiter.granted) {
                        waiters.remove(waiter)
                    } else {
                        // 授予与取消竞态：名额已经算在本任务头上，必须归还
                        usedBytes -= bytes
                        releaseGranted = true
                    }
                }
                if (releaseGranted) drainLocked()
                throw e
            }
            if (waiter.granted) return
        }
    }

    private suspend fun release(bytes: Long) {
        lock.withLock { usedBytes = (usedBytes - bytes).coerceAtLeast(0L) }
        drainLocked()
    }

    /** 按 FIFO 授予：队首放不下就停，避免大任务被源源不断的小任务饿死。 */
    private suspend fun drainLocked() {
        val granted = mutableListOf<Waiter>()
        lock.withLock {
            while (waiters.isNotEmpty()) {
                val head = waiters.first()
                if (usedBytes + head.bytes > totalBytes) break
                waiters.removeFirst()
                usedBytes += head.bytes
                head.granted = true
                granted += head
            }
        }
        granted.forEach { it.signal.complete(Unit) }
    }
}
