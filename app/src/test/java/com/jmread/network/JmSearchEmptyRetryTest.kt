package com.jmread.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索「假空」换域重试。
 *
 * 背景（2026-11-02 实测）：同一关键词、同一排序，服务端偶发返回 HTTP 200 + total=0 +
 * 空列表（24 次压测命中 1 次），同时其他镜像有数据。此前 200 空响应被直接当成无结果，
 * 用户会看到「搜 A 有数据、搜 A+B 没数据」的随机现象。
 */
class JmSearchEmptyRetryTest {

    @Test
    fun `综合维度的非空关键词允许空结果重试`() {
        assertTrue(JmClient.shouldRetryEmptySearch("回流女友", mainTag = 0))
    }

    @Test
    fun `作品作者标签维度不重试`() {
        // 这三个维度的 0 条是常态（如「回流」在作品维度根本无结果），重试只会浪费请求
        assertFalse(JmClient.shouldRetryEmptySearch("回流女友", mainTag = 1))
        assertFalse(JmClient.shouldRetryEmptySearch("回流女友", mainTag = 2))
        assertFalse(JmClient.shouldRetryEmptySearch("回流女友", mainTag = 3))
    }

    @Test
    fun `空白关键词不重试`() {
        assertFalse(JmClient.shouldRetryEmptySearch("", mainTag = 0))
        assertFalse(JmClient.shouldRetryEmptySearch("   ", mainTag = 0))
    }

    @Test
    fun `空结果会换线路重试直到拿到数据`() = runTest {
        var calls = 0
        var rotations = 0
        val result = JmClient.retryOnSuspiciousEmpty(
            maxRetries = 2,
            fetch = {
                calls++
                if (calls == 1) emptyList() else listOf("hit")
            },
            isEmpty = { it.isEmpty() },
            rotate = { rotations++ },
        )

        assertEquals(listOf("hit"), result)
        assertEquals(1, rotations)
        assertEquals(2, calls)
    }

    @Test
    fun `非空结果不触发重试`() = runTest {
        var rotations = 0
        val result = JmClient.retryOnSuspiciousEmpty(
            maxRetries = 2,
            fetch = { listOf("hit") },
            isEmpty = { it.isEmpty() },
            rotate = { rotations++ },
        )

        assertEquals(listOf("hit"), result)
        assertEquals(0, rotations)
    }

    @Test
    fun `连续空结果最多重试设定次数`() = runTest {
        var calls = 0
        var rotations = 0
        val result = JmClient.retryOnSuspiciousEmpty(
            maxRetries = 2,
            fetch = {
                calls++
                emptyList<String>()
            },
            isEmpty = { it.isEmpty() },
            rotate = { rotations++ },
        )

        assertTrue(result.isEmpty())
        assertEquals(2, rotations)
        assertEquals(3, calls) // 首次请求 + 2 次换线路重试
    }

    @Test
    fun `重试网络失败时保留原来的空响应`() = runTest {
        var calls = 0
        val result = JmClient.retryOnSuspiciousEmpty(
            maxRetries = 2,
            fetch = {
                calls++
                if (calls == 1) emptyList<String>() else error("network down")
            },
            isEmpty = { it.isEmpty() },
            rotate = {},
        )

        // 首次是成功的空响应；重试失败不应把结果升级成「搜索失败」。
        // 失败的那次仍会继续换下一条线路，所以最多打满 首次 + 2 次重试。
        assertTrue(result.isEmpty())
        assertEquals(3, calls)
    }

    @Test
    fun `首次请求异常仍按原语义抛出`() = runTest {
        var thrown = false
        try {
            JmClient.retryOnSuspiciousEmpty(
                maxRetries = 2,
                fetch = { throw IllegalStateException("all hosts failed") },
                isEmpty = { true },
                rotate = {},
            )
        } catch (e: IllegalStateException) {
            thrown = true
        }

        assertTrue(thrown)
    }
}
