package com.jmread.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialTagsTest {

    @Test
    fun `tag wall keeps four official groups`() {
        assertEquals(4, OfficialTags.groups.size)
        assertEquals(
            listOf("主题A漫", "角色 / 扮演", "特殊PLAY", "其他"),
            OfficialTags.groups.map { it.title },
        )
    }

    @Test
    fun `all tags are present and unique`() {
        val flattened = OfficialTags.groups.flatMap { it.tags }
        // 线上 /categories.blocks 共 47 个标签；词表固化后必须保持完整
        assertEquals(47, flattened.size)
        assertEquals(flattened.size, flattened.distinct().size)
        assertEquals(flattened, OfficialTags.all)
    }

    @Test
    fun `tags are trimmed and non blank`() {
        OfficialTags.all.forEach { tag ->
            assertTrue("标签不能为空: '$tag'", tag.isNotBlank())
            assertEquals("标签不应带首尾空白: '$tag'", tag, tag.trim())
        }
    }

    @Test
    fun `representative tags from each group exist`() {
        val all = OfficialTags.all
        listOf("NTR", "校园", "巨乳", "女僕", "阿黑颜", "兽交", "CG集", "非H").forEach {
            assertTrue("缺少标签 $it", it in all)
        }
    }
}
