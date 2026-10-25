package com.jmread.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun `空白词被忽略`() {
        assertEquals(listOf("旧词"), SearchHistory.sanitize(listOf("旧词"), "   "))
        assertEquals(emptyList<String>(), SearchHistory.sanitize(emptyList(), ""))
    }

    @Test
    fun `新词置顶`() {
        val next = SearchHistory.sanitize(listOf("a", "b"), "c")
        assertEquals(listOf("c", "a", "b"), next)
    }

    @Test
    fun `重复词去重并提到最前`() {
        val next = SearchHistory.sanitize(listOf("a", "b", "c"), "b")
        assertEquals(listOf("b", "a", "c"), next)
    }

    @Test
    fun `前后空白被 trim`() {
        val next = SearchHistory.sanitize(listOf("a"), "  姐姐  ")
        assertEquals(listOf("姐姐", "a"), next)
    }

    @Test
    fun `超过上限丢最旧`() {
        // 表内「新→旧」排列：w1 最新、w10 最旧
        val full = (1..SearchHistory.MAX_ENTRIES).map { "w$it" }
        val next = SearchHistory.sanitize(full, "新词")
        assertEquals(SearchHistory.MAX_ENTRIES, next.size)
        assertEquals("新词", next.first())
        // 最旧的 w10 被挤出，w1 保留
        assertEquals("w${SearchHistory.MAX_ENTRIES - 1}", next.last())
        assertEquals(false, "w${SearchHistory.MAX_ENTRIES}" in next)
    }
}
