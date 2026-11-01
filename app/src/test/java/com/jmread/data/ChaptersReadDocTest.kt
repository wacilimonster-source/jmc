package com.jmread.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-05/P1-08：已读章节身份与 order 回退语义。 */
class ChaptersReadDocTest {

    @Test
    fun `稳定 chapterId 优先于 order 回退`() {
        assertEquals("photo-123", ChaptersReadDoc.keyOf("photo-123", 2))
        assertEquals("order:2", ChaptersReadDoc.keyOf(null, 2))
        assertEquals("order:2", ChaptersReadDoc.keyOf("", 2))
    }

    @Test
    fun `keys 同时包含稳定 id 与回退身份`() {
        val doc = ChaptersReadDoc(chapterIds = listOf("a"), orders = listOf(3))
        assertEquals(setOf("a", "order:3"), doc.keys)
    }

    @Test
    fun `空文档没有已读身份`() {
        assertTrue(ChaptersReadDoc().keys.isEmpty())
    }
}
