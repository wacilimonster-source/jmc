package com.jmread.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * /promote 的 data 双形态回归：
 * 实测同一端点会轮换 `{"list": [...]}` 对象形态（2026-09-25）与裸数组形态（2026-10-25），
 * 只认一种时另一种整包解析失败、被 promoteBlocks 的 runCatching 吞成空列表。
 */
class JmPromoteShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `对象形态 data=list 解析出 blocks`() {
        val text = """
            {"code":200,"errorMsg":"","data":{"list":[
              {"id":"26","title":"连载更新","slug":"","type":"promote","filter_val":"26","content":[]}
            ]}}
        """.trimIndent()
        val resp = json.decodeFromString(JmPromoteResponse.serializer(), text)
        assertEquals(200, resp.code)
        assertEquals(1, resp.data.list.size)
        assertEquals("连载更新", resp.data.list[0].title)
    }

    @Test
    fun `裸数组形态直接给 blocks 同样解析`() {
        val text = """
            {"code":200,"errorMsg":"","data":[
              {"id":"26","title":"连载更新","type":"promote","content":[]},
              {"id":"27","title":"编辑推荐","type":"promote","content":[]}
            ]}
        """.trimIndent()
        val resp = json.decodeFromString(JmPromoteResponse.serializer(), text)
        assertEquals(2, resp.data.list.size)
        assertEquals("编辑推荐", resp.data.list[1].title)
    }

    @Test
    fun `空数组形态解析为空列表不抛异常`() {
        val text = """{"code":200,"errorMsg":"","data":[]}"""
        val resp = json.decodeFromString(JmPromoteResponse.serializer(), text)
        assertEquals(0, resp.data.list.size)
    }

    @Test
    fun `对象缺 list 键时回退空列表不抛异常`() {
        val text = """{"code":200,"errorMsg":"","data":{}}"""
        val resp = json.decodeFromString(JmPromoteResponse.serializer(), text)
        assertEquals(0, resp.data.list.size)
    }
}
