package com.jmread.data

import com.jmread.core.model.ComicCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-06：分类排序/隐藏合并规则。 */
class CategorySettingsTest {

    private fun category(id: String, title: String = id) = ComicCategory(id = id, title = title)

    private val server = listOf(
        category("a", "同人志"),
        category("b", "单本"),
        category("c", "短篇"),
    )

    @Test
    fun `按保存顺序排列，未知 id 忽略`() {
        val ordered = orderedCategories(
            server,
            CategorySettings.Settings(order = listOf("c", "missing", "a")),
        )
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun `服务端新增分类追加到末尾`() {
        val ordered = orderedCategories(
            server + category("d", "新分类"),
            CategorySettings.Settings(order = listOf("b")),
        )
        assertEquals(listOf("b", "a", "c", "d"), ordered.map { it.id })
    }

    @Test
    fun `隐藏分类不进入展示列表`() {
        val visible = applyCategorySettings(
            server,
            CategorySettings.Settings(order = listOf("b", "a", "c"), hidden = setOf("c")),
        )
        assertEquals(listOf("b", "a"), visible.map { it.id })
    }

    @Test
    fun `全隐藏时防御性回退为全部`() {
        val visible = applyCategorySettings(
            server,
            CategorySettings.Settings(hidden = server.map { it.id }.toSet()),
        )
        assertEquals(server.map { it.id }, visible.map { it.id })
    }

    @Test
    fun `空设置保持服务端顺序`() {
        val visible = applyCategorySettings(server, CategorySettings.Settings())
        assertEquals(server.map { it.id }, visible.map { it.id })
        assertTrue(visible.isNotEmpty())
    }
}
