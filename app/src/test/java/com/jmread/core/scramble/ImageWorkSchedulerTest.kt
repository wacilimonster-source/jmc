package com.jmread.core.scramble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** PERF-02：图片传输的全局/每 host/分类配额。 */
class ImageWorkSchedulerTest {

    @Before
    fun setUp() {
        ImageWorkScheduler.resetForTest()
    }

    @After
    fun tearDown() {
        ImageWorkScheduler.resetForTest()
    }

    @Test
    fun `普通档全局最多 4 路`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val peak = AtomicInteger()
        val jobs = (1..8).map {
            launch {
                ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") {
                    peak.accumulateAndGet(ImageWorkScheduler.activeCount) { a, b -> maxOf(a, b) }
                    gate.await()
                }
            }
        }
        delay(100)
        assertEquals(4, ImageWorkScheduler.activeCount)
        assertTrue(peak.get() <= 4)
        gate.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(0, ImageWorkScheduler.activeCount)
        assertEquals(0, ImageWorkScheduler.activeForHost("h"))
    }

    @Test
    fun `低优先级不占用可见页保留槽位`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val visible = (1..2).map {
            launch {
                ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") { gate.await() }
            }
        }
        delay(50)
        assertEquals(2, ImageWorkScheduler.activeCount)

        val downloadStarted = CompletableDeferred<Unit>()
        val download = launch {
            ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.DOWNLOAD, "h") {
                downloadStarted.complete(Unit)
                gate.await()
            }
        }
        delay(50)
        assertTrue("下载不应占用保留槽位", !downloadStarted.isCompleted)

        gate.complete(Unit)
        visible.forEach { it.join() }
        download.join()
    }

    @Test
    fun `预取最多 1 路`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val jobs = (1..3).map {
            launch {
                ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.PREFETCH, "h") {
                    started.incrementAndGet()
                    gate.await()
                }
            }
        }
        delay(80)
        assertEquals(1, started.get())
        gate.complete(Unit)
        jobs.forEach { it.join() }
    }

    @Test
    fun `低内存档全局 2 路且预取快速失败`() = runBlocking {
        ImageWorkScheduler.configure(lowRam = true)
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..4).map {
            launch {
                ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") { gate.await() }
            }
        }
        delay(80)
        assertEquals(2, ImageWorkScheduler.activeCount)
        try {
            ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.PREFETCH, "h") {
                fail("低内存档预取不应执行")
            }
            fail("应抛出 PrefetchDisabledException")
        } catch (e: ImageWorkScheduler.PrefetchDisabledException) {
            // expected
        }
        gate.complete(Unit)
        jobs.forEach { it.join() }
    }

    @Test
    fun `等待者取消后配额归还`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val holders = (1..4).map {
            launch {
                ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") { gate.await() }
            }
        }
        delay(50)
        assertEquals(4, ImageWorkScheduler.activeCount)

        val waiter = launch {
            ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") {
                fail("取消的等待者不应获得配额")
            }
        }
        delay(50)
        waiter.cancelAndJoin()
        gate.complete(Unit)
        holders.forEach { it.join() }
        assertEquals(0, ImageWorkScheduler.activeCount)
    }

    @Test
    fun `同 host 配额独立于全局`() = runBlocking {
        ImageWorkScheduler.configure(lowRam = false)
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..6).map {
            launch {
                ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h1") { gate.await() }
            }
        }
        delay(80)
        assertEquals(4, ImageWorkScheduler.activeForHost("h1"))
        gate.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(0, ImageWorkScheduler.activeForHost("h1"))
    }

    @Test
    fun `取消不泄漏且后续请求可立即获得配额`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") { gate.await() }
        }
        delay(30)
        job.cancelAndJoin()
        withTimeout(1_000) {
            ImageWorkScheduler.withSlot(ImageWorkScheduler.Priority.VISIBLE, "h") { }
        }
        assertEquals(0, ImageWorkScheduler.activeCount)
    }
}
