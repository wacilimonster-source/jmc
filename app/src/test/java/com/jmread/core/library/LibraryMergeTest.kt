package com.jmread.core.library

import com.jmread.core.model.ComicSummary
import com.jmread.data.Bookmarks
import com.jmread.data.ReadStatus
import com.jmread.data.RecentRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMergeTest {

    private fun bookmark(id: String, addedAt: Long, title: String = "本地标题$id") =
        Bookmarks.BookmarkItem(comicId = id, title = title, author = "本地作者", coverUrl = "local://$id", addedAt = addedAt)

    private fun recent(id: String, ts: Long, order: Int = 1, page: Int = 0) = RecentRead(
        comicId = id, title = "历史标题$id", coverUrl = "recent://$id", author = "历史作者",
        order = order, pageIndex = page, ts = ts,
    )

    private fun cloudFav(id: String, latestEp: String = "") = ComicSummary(
        id = id, title = "云端标题$id", author = "云端作者", coverUrl = "cloud://$id",
        latestEpText = latestEp,
    )

    private fun cloudHist(id: String) = ComicSummary(
        id = id, title = "云端历史标题$id", author = "云端历史作者", coverUrl = "cloudh://$id",
    )

    @Test
    fun `四源同 id 并集为一条且本地进度权威`() {
        val merged = LibraryMerge.merge(
            bookmarks = listOf(bookmark("1", addedAt = 100)),
            recents = listOf(recent("1", ts = 500, order = 3, page = 7)),
            cloudFavourites = listOf(cloudFav("1")),
            cloudHistory = listOf(cloudHist("1")),
            statusOf = { null },
        )
        assertEquals(1, merged.size)
        val item = merged.single()
        assertTrue(item.collected)
        assertEquals(100L, item.collectedAt)
        assertEquals(500L, item.lastReadAt)
        assertEquals(3, item.lastOrder)
        assertEquals(7, item.lastPage)
        // 续读行以本地历史为准（精确到话+页）
        assertEquals("续读 · 第 3 话 · 第 8 页", item.statusLine)
    }

    @Test
    fun `云端 only 收藏条目无进度不伪造续读`() {
        val merged = LibraryMerge.merge(
            bookmarks = emptyList(),
            recents = emptyList(),
            cloudFavourites = listOf(cloudFav("9")),
            cloudHistory = emptyList(),
            statusOf = { null },
        )
        val item = merged.single()
        assertTrue(item.collected)
        assertNull(item.lastOrder)
        assertNull(item.statusLine)
        assertEquals(0L, item.lastActivityAt)
    }

    @Test
    fun `云端 only 历史条目并入且不误标收藏`() {
        val merged = LibraryMerge.merge(
            bookmarks = emptyList(),
            recents = emptyList(),
            cloudFavourites = emptyList(),
            cloudHistory = listOf(cloudHist("8")),
            statusOf = { null },
        )
        val item = merged.single()
        assertFalse(item.collected)
        assertNull(item.lastOrder)
        assertNull(item.statusLine)
    }

    @Test
    fun `最近动态排序_本地活跃在上_云端only沉底保持服务端序`() {
        val merged = LibraryMerge.merge(
            bookmarks = listOf(bookmark("2", addedAt = 200)),
            recents = listOf(recent("1", ts = 999)),
            cloudFavourites = listOf(cloudFav("5"), cloudFav("4")),
            cloudHistory = listOf(cloudHist("3")),
            statusOf = { null },
        )
        // 本地条目按 lastActivity 降序：历史(999) > 收藏(200)
        assertEquals(listOf("1", "2"), merged.take(2).map { it.comicId })
        // 云端 only 条目时间戳为 0 沉底，稳定排序保留服务端相对序（收藏序 5>4，历史 3 在收藏后）
        assertEquals(listOf("5", "4", "3"), merged.drop(2).map { it.comicId })
    }

    @Test
    fun `读完状态与在读筛选`() {
        val merged = LibraryMerge.merge(
            bookmarks = emptyList(),
            recents = listOf(recent("1", ts = 1), recent("2", ts = 2)),
            cloudFavourites = emptyList(),
            cloudHistory = emptyList(),
            statusOf = { if (it == "2") ReadStatus.FINISHED else null },
        )
        val finished = merged.single { it.comicId == "2" }
        val reading = merged.single { it.comicId == "1" }
        assertTrue(finished.finished)
        assertEquals("已读完", finished.statusLine)
        assertFalse(reading.finished)
        assertTrue(LibraryFilter.FINISHED.matches(finished))
        assertFalse(LibraryFilter.READING.matches(finished))
        assertTrue(LibraryFilter.READING.matches(reading))
        assertFalse(LibraryFilter.COLLECTED.matches(reading))
        assertTrue(LibraryFilter.ALL.matches(reading))
    }

    @Test
    fun `有更新角标只来自云端收藏 latest_ep`() {
        val merged = LibraryMerge.merge(
            bookmarks = emptyList(),
            recents = emptyList(),
            cloudFavourites = listOf(cloudFav("1", latestEp = "第 10 话"), cloudFav("2")),
            cloudHistory = emptyList(),
            statusOf = { null },
        )
        assertTrue(merged.single { it.comicId == "1" }.hasUpdate)
        assertFalse(merged.single { it.comicId == "2" }.hasUpdate)
        // 有更新条目转 ComicSummary 时携带 latestEpText（ComicGridView 角标契约）
        assertEquals("有更新", merged.single { it.comicId == "1" }.toSummary().latestEpText)
    }

    @Test
    fun `元数据云端优先本地兜底`() {
        val merged = LibraryMerge.merge(
            bookmarks = listOf(bookmark("1", addedAt = 1)),
            recents = emptyList(),
            cloudFavourites = listOf(cloudFav("1")),
            cloudHistory = emptyList(),
            statusOf = { null },
        )
        val item = merged.single()
        assertEquals("云端标题1", item.title)
        assertEquals("cloud://1", item.coverUrl)
    }

    @Test
    fun `纯本地无云端来源时元数据用本地`() {
        val merged = LibraryMerge.merge(
            bookmarks = listOf(bookmark("1", addedAt = 1)),
            recents = listOf(recent("2", ts = 2)),
            cloudFavourites = emptyList(),
            cloudHistory = emptyList(),
            statusOf = { null },
        )
        assertEquals("本地标题1", merged.single { it.comicId == "1" }.title)
        assertEquals("local://1", merged.single { it.comicId == "1" }.coverUrl)
        assertEquals("历史标题2", merged.single { it.comicId == "2" }.title)
    }
}
