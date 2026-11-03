package com.jmread.core.scramble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * 图片传输的全局配额（PERF-02）。
 *
 * 背景：`BcTls.imageLoaderClient` 的 OkHttp Dispatcher 上限只约束 `enqueue()` 的
 * 异步队列；自定义 Fetcher 早期用 `Call.execute()` 同步下载，完全绕过该上限。
 * 这里把「可见页 / 预取 / 下载」三类调用统一到同一组配额：
 *
 *  - 普通档：全局 4 路、同 host ≤4；预取 ≤1、下载 ≤1；始终为可见页保留 2 个位置；
 *  - 低内存档：全局 2 路、预取 0、下载 ≤1，仍为可见页保留 1 个位置；
 *  - 配额按实际传输的 host 记账，换 CDN 不会额外获得新配额；
 *  - 等待可取消；低优先级只使用非保留槽位，可见页不会被预取/下载饿死。
 *
 * 纯 Kotlin 实现，便于 JVM 单测。
 */
object ImageWorkScheduler {

    enum class Priority(val rank: Int) {
        /** 当前可见页（含切片） */
        VISIBLE(0),

        /** 相邻页预取 */
        PREFETCH(1),

        /** 后台下载 */
        DOWNLOAD(2),
    }

    /** 低内存档预取配额为 0：快速失败，不进入排队。 */
    class PrefetchDisabledException : IOException("低内存档已禁用图片预取")

    data class Limits(
        val global: Int,
        val perHost: Int,
        val prefetch: Int,
        val download: Int,
        /** 低优先级任务永远不能占用的槽位数（留给可见页） */
        val visibleReserve: Int,
    )

    private val NORMAL = Limits(global = 4, perHost = 4, prefetch = 1, download = 1, visibleReserve = 2)
    private val LOW_RAM = Limits(global = 2, perHost = 2, prefetch = 0, download = 1, visibleReserve = 1)

    private class Waiter(
        val priority: Priority,
        val host: String?,
        val seq: Long,
    ) {
        @Volatile var granted = false
        val signal = CompletableDeferred<Unit>()
    }

    private val lock = Mutex()
    private val waiters = ArrayList<Waiter>()
    private var seq = 0L

    private var used = 0
    private var prefetchUsed = 0
    private var downloadUsed = 0
    private val perHost = HashMap<String, Int>()

    @Volatile private var limits: Limits = NORMAL

    fun configure(lowRam: Boolean) {
        limits = if (lowRam) LOW_RAM else NORMAL
    }

    /** 当前在途传输数（测试/诊断用） */
    val activeCount: Int get() = used
    val activePrefetch: Int get() = prefetchUsed
    val activeDownload: Int get() = downloadUsed

    fun activeForHost(host: String): Int = perHost[host] ?: 0

    /** 仅供单测复位（单例在 JVM 内跨测试共享） */
    fun resetForTest() {
        limits = NORMAL
        used = 0
        prefetchUsed = 0
        downloadUsed = 0
        perHost.clear()
        waiters.clear()
    }

    /**
     * 在配额内执行一次传输。等待可取消，退出时（含异常/取消）归还配额。
     * @param host 实际请求的 host（换线后应传新 host）
     */
    suspend fun <T> withSlot(
        priority: Priority,
        host: String?,
        block: suspend () -> T,
    ): T {
        // 低内存档预取配额为 0：快速失败，绝不能让预取请求无限排队
        if (priority == Priority.PREFETCH && limits.prefetch <= 0) {
            throw PrefetchDisabledException()
        }
        val waiter = Waiter(priority, host, ++seq)
        acquire(waiter)
        try {
            return block()
        } finally {
            release(waiter)
        }
    }

    private suspend fun acquire(waiter: Waiter) {
        var registered = false
        while (true) {
            val grantedNow = lock.withLock {
                if (!registered) {
                    if (waiters.isEmpty() && canGrantLocked(waiter)) {
                        grantLocked(waiter)
                        return@withLock true
                    }
                    waiters.add(waiter)
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
                        releaseCountersLocked(waiter)
                        releaseGranted = true
                    }
                }
                if (releaseGranted) drainLocked()
                throw e
            }
            if (waiter.granted) return
        }
    }

    private suspend fun release(waiter: Waiter) {
        lock.withLock { releaseCountersLocked(waiter) }
        drainLocked()
    }

    private fun canGrantLocked(waiter: Waiter): Boolean {
        val limit = limits
        val effectiveGlobal = if (waiter.priority == Priority.VISIBLE) {
            limit.global
        } else {
            (limit.global - limit.visibleReserve).coerceAtLeast(0)
        }
        if (used >= effectiveGlobal) return false
        val host = waiter.host
        if (host != null && (perHost[host] ?: 0) >= limit.perHost) return false
        when (waiter.priority) {
            Priority.VISIBLE -> Unit
            Priority.PREFETCH -> if (prefetchUsed >= limit.prefetch) return false
            Priority.DOWNLOAD -> if (downloadUsed >= limit.download) return false
        }
        return true
    }

    private fun grantLocked(waiter: Waiter) {
        used++
        when (waiter.priority) {
            Priority.VISIBLE -> Unit
            Priority.PREFETCH -> prefetchUsed++
            Priority.DOWNLOAD -> downloadUsed++
        }
        waiter.host?.let { perHost[it] = (perHost[it] ?: 0) + 1 }
        waiter.granted = true
    }

    private fun releaseCountersLocked(waiter: Waiter) {
        if (!waiter.granted) return
        waiter.granted = false
        used = (used - 1).coerceAtLeast(0)
        when (waiter.priority) {
            Priority.VISIBLE -> Unit
            Priority.PREFETCH -> prefetchUsed = (prefetchUsed - 1).coerceAtLeast(0)
            Priority.DOWNLOAD -> downloadUsed = (downloadUsed - 1).coerceAtLeast(0)
        }
        waiter.host?.let { host ->
            val next = (perHost[host] ?: 0) - 1
            if (next <= 0) perHost.remove(host) else perHost[host] = next
        }
    }

    /** 按「可见 > 预取 > 下载」+ FIFO 授予；每次释放后补满可授予的等待者。 */
    private suspend fun drainLocked() {
        val granted = mutableListOf<Waiter>()
        lock.withLock {
            while (true) {
                val next = waiters
                    .asSequence()
                    .filter { canGrantLocked(it) }
                    .minWithOrNull(compareBy<Waiter>({ it.priority.rank }, { it.seq }))
                    ?: break
                waiters.remove(next)
                grantLocked(next)
                granted += next
            }
        }
        granted.forEach { it.signal.complete(Unit) }
    }
}
