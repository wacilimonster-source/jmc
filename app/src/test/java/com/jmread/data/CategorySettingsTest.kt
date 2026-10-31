package com.jmread.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorySettingsTest {

    @Test
    fun `togglePinnedTag adds new tag and preserves order`() {
        val current = CategorySettings.Settings(pinnedTags = listOf("NTR"))
        val next = CategorySettings.togglePinnedTag(current, "巨乳")

        assertEquals(listOf("NTR", "巨乳"), next.pinnedTags)
    }

    @Test
    fun `togglePinnedTag removes existing tag`() {
        val current = CategorySettings.Settings(pinnedTags = listOf("NTR", "巨乳"))
        val next = CategorySettings.togglePinnedTag(current, "NTR")

        assertEquals(listOf("巨乳"), next.pinnedTags)
    }

    @Test
    fun `togglePinnedTag ignores blank tags`() {
        val current = CategorySettings.Settings(pinnedTags = listOf("NTR"))

        assertEquals(current, CategorySettings.togglePinnedTag(current, " "))
    }

    @Test
    fun `togglePinnedTag keeps the earliest pinned tags when over limit`() {
        val tags = (1..CategorySettings.MAX_PINNED_TAGS + 2).map { "tag$it" }
        var current = CategorySettings.Settings()

        tags.forEach { tag -> current = CategorySettings.togglePinnedTag(current, tag) }

        assertEquals(CategorySettings.MAX_PINNED_TAGS, current.pinnedTags.size)
        assertTrue(current.pinnedTags.first() == "tag1")
        assertTrue(current.pinnedTags.last() == "tag${CategorySettings.MAX_PINNED_TAGS}")
    }
}
