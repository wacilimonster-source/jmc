package com.jmread.ui.browse

import com.jmread.core.model.ComicSummary
import com.jmread.data.ReaderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地阅读筛选「先过滤后分页」回归（I04 阶段 B）。
 *
 * 旧实现先按 20 条切片再过滤：第一片全被过滤掉时显示空页，
 * 即使其它分片仍有命中；新实现先过滤已加载集合再切片。
 * ReaderStatus 是进程内单例：用例间以独立 id 前缀隔离，并在用例内自清。
 */
class FilterPagingTest {

    private fun comic(id: String) = ComicSummary(id = id, title = "title-$id")

    private fun resetStatuses(ids: List<String>) {
        ids.forEach { ReaderStatus.clear(it) }
    }

    @Test
    fun `首片无命中时第 1 页仍能展示其它分片的匹配（旧实现为空页）`() {
        val loaded = (1..60).map { n ->
            comic(if (n == 21 || n == 41) "fp1-hit-$n" else "fp1-miss-$n")
        }
        // 未命中项标记已读完 → UNFINISHED 只匹配两个 hit；它们分别在第 2、3 个服务端分片
        loaded.filter { it.id.contains("miss") }.forEach { ReaderStatus.markFinished(it.ref) }

        val page1 = filterThenPage(loaded, ReadFilter.UNFINISHED, 1)

        assertEquals(listOf("fp1-hit-21", "fp1-hit-41"), page1.map { it.id })
        assertEquals(1, filteredPageCount(2))
        resetStatuses(loaded.map { it.ref })
    }

    @Test
    fun `连续匹配跨页切片正确`() {
        val loaded = (1..45).map { comic("fp2-c$it") } // 全部未读 → UNREAD 全匹配
        val page1 = filterThenPage(loaded, ReadFilter.UNREAD, 1)
        val page2 = filterThenPage(loaded, ReadFilter.UNREAD, 2)
        val page3 = filterThenPage(loaded, ReadFilter.UNREAD, 3)

        assertEquals(20, page1.size)
        assertEquals(20, page2.size)
        assertEquals(5, page3.size)
        assertEquals("fp2-c1", page1.first().id)
        assertEquals("fp2-c21", page2.first().id)
        assertEquals("fp2-c41", page3.first().id)
        assertEquals(3, filteredPageCount(45))
        resetStatuses(loaded.map { it.ref })
    }

    @Test
    fun `页码越界返回空而不崩溃`() {
        val loaded = (1..10).map { comic("fp3-x$it") }
        assertTrue(filterThenPage(loaded, ReadFilter.UNREAD, 2).isEmpty())
        assertTrue(filterThenPage(loaded, ReadFilter.UNREAD, 99).isEmpty())
        assertEquals(1, filteredPageCount(10))
        resetStatuses(loaded.map { it.ref })
    }

    @Test
    fun `空集合与 ALL 直通`() {
        assertTrue(filterThenPage(emptyList(), ReadFilter.UNREAD, 1).isEmpty())
        val loaded = (1..25).map { comic("fp4-a$it") }
        // ALL 模式不切片：分页语义仍由调用方的服务端分页承担
        assertEquals(25, filterThenPage(loaded, ReadFilter.ALL, 1).size)
        resetStatuses(loaded.map { it.ref })
    }

    @Test
    fun `页数计算 - 边界值`() {
        assertEquals(1, filteredPageCount(0))
        assertEquals(1, filteredPageCount(1))
        assertEquals(1, filteredPageCount(20))
        assertEquals(2, filteredPageCount(21))
        assertEquals(5, filteredPageCount(81))
    }
}
