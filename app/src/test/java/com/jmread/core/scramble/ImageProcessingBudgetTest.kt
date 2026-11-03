package com.jmread.core.scramble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PERF-01：跨入口共享的图片处理预算。 */
class ImageProcessingBudgetTest {

    @After
    fun reset() {
        ImageProcessingBudget.configure(128L * 1024 * 1024, parallelism = 2)
    }

    @Test
    fun `并发任务从不超发预算`() = runBlocking {
        ImageProcessingBudget.configure(totalBytes = 100, parallelism = 2)
        val peak = java.util.concurrent.atomic.AtomicLong()
        val jobs = (1..8).map {
            launch {
                ImageProcessingBudget.withBudget(30) {
                    val now = ImageProcessingBudget.inUse
                    peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    delay(20)
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("峰值 ${peak.get()} 超过总预算", peak.get() <= 100)
        assertEquals(0L, ImageProcessingBudget.inUse)
    }

    @Test
    fun `单任务超过总预算立即拒绝而不是永久等待`() = runBlocking {
        ImageProcessingBudget.configure(totalBytes = 100, parallelism = 2)
        try {
            ImageProcessingBudget.withBudget(101) { fail("不应进入 block") }
            fail("应当抛出 ExceededException")
        } catch (e: ImageProcessingBudget.ExceededException) {
            assertEquals(101L, e.requestedBytes)
            assertEquals(100L, e.totalBytes)
        }
        assertEquals(0L, ImageProcessingBudget.inUse)
    }

    @Test
    fun `等待者取消后名额归还`() = runBlocking {
        ImageProcessingBudget.configure(totalBytes = 100, parallelism = 2)
        val holderStarted = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = launch {
            ImageProcessingBudget.withBudget(100) {
                holderStarted.complete(Unit)
                releaseHolder.await()
            }
        }
        holderStarted.await()
        assertEquals(100L, ImageProcessingBudget.inUse)

        val waiter = launch {
            ImageProcessingBudget.withBudget(50) { fail("取消的等待者不应获得预算") }
        }
        delay(50)
        waiter.cancelAndJoin()

        releaseHolder.complete(Unit)
        holder.join()
        assertEquals(0L, ImageProcessingBudget.inUse)
    }

    @Test
    fun `异常路径不泄漏预算`() = runBlocking {
        ImageProcessingBudget.configure(totalBytes = 100, parallelism = 2)
        try {
            ImageProcessingBudget.withBudget(60) { error("boom") }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(0L, ImageProcessingBudget.inUse)
        withTimeout(1_000) {
            val result = async { ImageProcessingBudget.withBudget(100) { 42 } }
            assertEquals(42, result.await())
        }
    }

    @Test
    fun `估算包含源与目标两份位图`() {
        val bytes = ImageProcessingBudget.estimateBytes(2160, 8192)
        // 2160*8192*8 + 2160*4 = 141,565,440 + 8,640
        assertEquals(2160L * 8192 * 8 + 2160L * 4, bytes)
        assertEquals(0L, ImageProcessingBudget.estimateBytes(0, 8192))
        assertEquals(0L, ImageProcessingBudget.estimateBytes(-1, -1))
        // Int 上限级尺寸不能因 Int 乘法溢出
        assertTrue(ImageProcessingBudget.estimateBytes(Int.MAX_VALUE, Int.MAX_VALUE) > 0)
    }

    @Test
    fun `单任务像素上限随总预算缩放`() {
        ImageProcessingBudget.configure(totalBytes = 90L * 1024 * 1024, parallelism = 2)
        assertEquals((90L * 1024 * 1024) / 8, ImageProcessingBudget.maxPixelsPerJob())
    }
}
