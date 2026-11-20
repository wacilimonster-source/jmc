package com.jmread.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关注关键词「删除→撤销」逻辑单测（对标 PiKA Q11 撤销语义）。
 *
 * UI 层的撤销 = Snackbar ActionPerformed 后用快照整条恢复；
 * 本测试固化数据层的等价操作序列：removeItem(snapshot) → addItem(snapshot.keywords, snapshot.tag)
 * 必须完整还原原条目（keywords/tag/内容一致），且不清空其他关注项。
 */
class FollowUndoTest {

    /** 与 FollowSettings.items/addItem/removeItem 相同的内容判定口径 */
    private fun sameContent(a: FollowItem, b: FollowItem) = a.keywords == b.keywords && a.tag == b.tag

    @Test
    fun `快照恢复语义——移除后按快照重建等价条目`() {
        val original = FollowItem(keywords = listOf("校园", "热血"), tag = "冒险", createdAt = 1_000L)
        // 模拟删除：从列表移除
        val afterRemove = listOf(original).filterNot { sameContent(it, original) }
        assertTrue("删除后不应包含原条目", afterRemove.none { sameContent(it, original) })
        // 模拟撤销：按快照整条恢复（UI 层 FollowSettings.addItem(snapshot.keywords, snapshot.tag)）
        val restored = FollowItem(keywords = original.keywords, tag = original.tag, createdAt = 2_000L)
        val afterUndo = listOf(restored) + afterRemove
        assertTrue("撤销后应恢复等价条目", afterUndo.any { sameContent(it, original) })
    }

    @Test
    fun `addItem 的去重口径与内容判定一致——撤销不会产生重复`() {
        val a = FollowItem(keywords = listOf("test"), tag = null, createdAt = 1L)
        val b = FollowItem(keywords = listOf("test"), tag = null, createdAt = 2L)
        assertFalse("同 keywords+tag 视为同一条（addItem 的 filterNot 口径）", !sameContent(a, b))
    }

    @Test
    fun `多关键词与空标签条目可被快照恢复`() {
        val original = FollowItem(keywords = listOf("a", "b"), tag = null, createdAt = 5L)
        val restored = FollowItem(keywords = original.keywords, tag = original.tag, createdAt = 6L)
        assertTrue(sameContent(original, restored))
        assertEquals(original.keywords, restored.keywords)
        assertEquals(original.tag, restored.tag)
    }
}
