package com.jmread.pagination

import com.jmread.core.model.BROWSE_HARD_CAP
import com.jmread.core.model.BROWSE_TOTAL_SENTINEL
import com.jmread.core.model.pagesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻页判据单测（PiKA D2 修复）：
 * 浏览流 total 恒为 10000（第 126 页起服务端原样重发第 125 页）→ 硬上限 125；
 * 搜索/榜单 total 真实 → 直接 ceil；total 不可信时退回「当页不满」判断。
 */
class PagesOfTest {

    @Test
    fun `浏览流哨兵 total 走当页条数判断并受 125 页硬上限约束`() {
        val pageSize = 80
        // 满页 → 还有下一页，但永远不超过 125
        assertEquals(2, pagesOf(BROWSE_TOTAL_SENTINEL, pageSize, 1, pageSize, BROWSE_HARD_CAP))
        assertEquals(125, pagesOf(BROWSE_TOTAL_SENTINEL, pageSize, 124, pageSize, BROWSE_HARD_CAP))
        assertEquals(125, pagesOf(BROWSE_TOTAL_SENTINEL, pageSize, 125, pageSize, BROWSE_HARD_CAP))
    }

    @Test
    fun `浏览流不满页即到底`() {
        assertEquals(7, pagesOf(BROWSE_TOTAL_SENTINEL, 30, 7, 80, BROWSE_HARD_CAP))
    }

    @Test
    fun `真实 total 直接 ceil`() {
        assertEquals(10, pagesOf(721, 80, 1, 80, BROWSE_HARD_CAP))
        assertEquals(10, pagesOf(721, 80, 9, 80, BROWSE_HARD_CAP))
        // 无上限时 ceil 不截断（搜索/评论页默认场景）
        assertEquals(143, pagesOf(143, 1, 1, 1))
        assertEquals(2, pagesOf(143, 80, 1, 80, BROWSE_HARD_CAP))
    }

    @Test
    fun `真实 total 也受硬上限保护`() {
        assertEquals(BROWSE_HARD_CAP, pagesOf(999999, 80, 1, 80, BROWSE_HARD_CAP))
    }

    @Test
    fun `total 为 0 时退回当页判断`() {
        assertEquals(2, pagesOf(0, 80, 1, 80))
        assertEquals(5, pagesOf(0, 10, 5, 80))
    }

    @Test
    fun `哨兵值约定与实测一致`() {
        assertEquals(10000, BROWSE_TOTAL_SENTINEL)
        assertEquals(125, BROWSE_HARD_CAP)
    }
}
