package com.jmread.ui.home

import com.jmread.core.PromoteBlock
import com.jmread.core.model.ComicSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页排行榜下方展开分区的映射规则：
 * 空 block 不占位、「连载更新」运营位隐藏、标题里的右滑提示截断。
 */
class HomePromoteSectionsTest {

    private fun comic(id: String) = ComicSummary(id = id, title = "漫画 $id")

    @Test
    fun `连载更新 block 被过滤（简体）`() {
        val blocks = listOf(
            PromoteBlock(id = "26", title = "连载更新→右滑看更多→", comics = listOf(comic("1"))),
            PromoteBlock(id = "29", title = "C108&推荐本本", comics = listOf(comic("2"))),
        )

        val sections = homePromoteSections(blocks)

        assertEquals(listOf("C108&推荐本本"), sections.map { it.title })
    }

    @Test
    fun `连载更新 block 被过滤（实机繁体标题）`() {
        val blocks = listOf(
            PromoteBlock(id = "26", title = "連載更新→右滑看更多→", comics = listOf(comic("1"))),
            PromoteBlock(id = "29", title = "C108&推薦本本", comics = listOf(comic("2"))),
        )

        val sections = homePromoteSections(blocks)

        assertEquals(listOf("C108&推薦本本"), sections.map { it.title })
    }

    @Test
    fun `空 block 不占位`() {
        val blocks = listOf(
            PromoteBlock(id = "30", title = "禁漫去码&全彩化", comics = emptyList()),
            PromoteBlock(id = "998", title = "禁漫汉化组", comics = listOf(comic("3"))),
        )

        assertEquals(listOf("禁漫汉化组"), homePromoteSections(blocks).map { it.title })
    }

    @Test
    fun `右滑提示被截断且保留 block key`() {
        val blocks = listOf(
            PromoteBlock(id = "31", title = "韩漫更新→右滑看更多→", comics = listOf(comic("4"))),
        )

        val section = homePromoteSections(blocks).single()

        assertEquals("韩漫更新", section.title)
        assertEquals("31", section.key)
        assertTrue(section.comics.isNotEmpty())
    }

    @Test
    fun `空标题回退为推荐`() {
        val blocks = listOf(
            PromoteBlock(id = "32", title = "→右滑看更多→", comics = listOf(comic("5"))),
        )

        assertEquals("推荐", homePromoteSections(blocks).single().title)
    }
}
