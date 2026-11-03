package com.jmread.core

import com.jmread.network.JmAlbumResponse
import com.jmread.network.JmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** PERF-04：同一 album 的并发首次请求合并。 */
class AlbumCoalescingTest {

    @Before
    fun setUp() {
        JmRepository.clearAlbumCache()
        JmRepository.albumFetcher = { JmAlbumResponse() }
    }

    @After
    fun tearDown() {
        JmRepository.clearAlbumCache()
        JmRepository.albumFetcher = { JmClient.album(it) }
    }

    @Test
    fun `同 id 20 个并发调用只有一次上游`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        JmRepository.albumFetcher = {
            calls.incrementAndGet()
            gate.await()
            JmAlbumResponse()
        }

        val jobs = (1..20).map {
            async(Dispatchers.Default) { JmRepository.albumCached("42") }
        }
        delay(250)
        assertEquals(1, calls.get())
        gate.complete(Unit)
        val results = jobs.awaitAll()
        assertEquals(20, results.size)
        assertEquals(1, calls.get())
    }

    @Test
    fun `不同 id 并发正常`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        JmRepository.albumFetcher = {
            calls.incrementAndGet()
            gate.await()
            JmAlbumResponse()
        }
        val jobs = listOf("1", "2").map {
            async(Dispatchers.Default) { JmRepository.albumCached(it) }
        }
        delay(250)
        assertEquals(2, calls.get())
        gate.complete(Unit)
        jobs.awaitAll()
        Unit
    }

    @Test
    fun `首个订阅者取消不影响其他订阅者`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        JmRepository.albumFetcher = {
            calls.incrementAndGet()
            gate.await()
            JmAlbumResponse()
        }

        val first = launch(Dispatchers.Default) { JmRepository.albumCached("x") }
        val second = async(Dispatchers.Default) { JmRepository.albumCached("x") }
        delay(250)
        assertEquals(1, calls.get())

        first.cancelAndJoin()
        gate.complete(Unit)
        second.await()
        assertEquals(1, calls.get())
    }

    @Test
    fun `全部订阅者取消后共享任务终止`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        JmRepository.albumFetcher = {
            calls.incrementAndGet()
            gate.await()
            JmAlbumResponse()
        }

        val first = launch(Dispatchers.Default) { JmRepository.albumCached("y") }
        delay(200)
        assertEquals(1, calls.get())
        first.cancelAndJoin()
        delay(150)

        // 旧共享任务已被取消；新调用应重新发起上游，而不是复用已取消的 Deferred
        val second = async(Dispatchers.Default) { JmRepository.albumCached("y") }
        delay(200)
        assertEquals(2, calls.get())
        gate.complete(Unit)
        second.await()
        Unit
    }

    @Test
    fun `失败不缓存且表项被清理`() = runBlocking {
        val calls = AtomicInteger()
        JmRepository.albumFetcher = {
            calls.incrementAndGet()
            if (calls.get() == 1) error("boom") else JmAlbumResponse()
        }

        try {
            JmRepository.albumCached("z")
            fail("首次应失败")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("boom") == true)
        }
        // 表项已清理：第二次应重新请求并成功
        JmRepository.albumCached("z")
        assertEquals(2, calls.get())
    }
}
