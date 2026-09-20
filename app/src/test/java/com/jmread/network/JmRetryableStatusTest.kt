package com.jmread.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换域触发条件（HTTP 状态分类）。
 *
 * 背景（2026-09-21 实测）：/search 在 4 个内置镜像上**随机**返回 HTTP 500（空体），
 * 同一时刻总有一个镜像返回 200。早期版本把所有非 2xx 都当业务错误直接上抛，
 * 5xx 不换域 —— 等于把「换条线路就好」变成硬失败，搜索约 40% 概率直接报错。
 */
class JmRetryableStatusTest {

    @Test
    fun `5xx 触发换域`() {
        assertTrue(JmClient.isRetryableHttpStatus(500))
        assertTrue(JmClient.isRetryableHttpStatus(502))
        assertTrue(JmClient.isRetryableHttpStatus(503))
        assertTrue(JmClient.isRetryableHttpStatus(504))
    }

    @Test
    fun `429 限流触发换域`() {
        assertTrue(JmClient.isRetryableHttpStatus(429))
    }

    @Test
    fun `业务类 4xx 不换域`() {
        // 端点不存在 / 参数非法 / 未登录：换域改变不了结果
        assertFalse(JmClient.isRetryableHttpStatus(400))
        assertFalse(JmClient.isRetryableHttpStatus(401))
        assertFalse(JmClient.isRetryableHttpStatus(403))
        assertFalse(JmClient.isRetryableHttpStatus(404))
        assertFalse(JmClient.isRetryableHttpStatus(422))
    }

    @Test
    fun `2xx 不算故障`() {
        assertFalse(JmClient.isRetryableHttpStatus(200))
        assertFalse(JmClient.isRetryableHttpStatus(204))
    }
}
