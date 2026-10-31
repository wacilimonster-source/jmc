package com.jmread.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 收藏同步对账决策的单测（docs/unified-library-design.md §4.2）：
 * 翻转端点非幂等，队列 flush 必须只对「云端仍与意图不符」的条目出牌。
 */
class FavouriteSyncPlanTest {

    private fun op(id: String, add: Boolean) = FavouriteSyncQueue.PendingOp(id, add, 0L)

    @Test
    fun `add意图_云端缺失才补翻转`() {
        val plan = FavouriteSyncPlan.plan(cloudIds = setOf("1"), pending = listOf(op("1", add = true), op("2", add = true)))
        assertEquals(FavouriteSyncPlan.Action.DROP, plan.first { it.comicId == "1" }.action)
        assertEquals(FavouriteSyncPlan.Action.TOGGLE_ADD, plan.first { it.comicId == "2" }.action)
    }

    @Test
    fun `remove意图_云端存在才补翻转`() {
        val plan = FavouriteSyncPlan.plan(cloudIds = setOf("1"), pending = listOf(op("1", add = false), op("2", add = false)))
        assertEquals(FavouriteSyncPlan.Action.TOGGLE_REMOVE, plan.first { it.comicId == "1" }.action)
        assertEquals(FavouriteSyncPlan.Action.DROP, plan.first { it.comicId == "2" }.action)
    }

    @Test
    fun `空队列与空云端集合边界`() {
        assertEquals(emptyList<FavouriteSyncPlan.Step>(), FavouriteSyncPlan.plan(emptySet(), emptyList()))
        val plan = FavouriteSyncPlan.plan(emptySet(), listOf(op("9", add = false)))
        // 云端本就没有该收藏，取消意图已满足 → 零请求出队
        assertEquals(FavouriteSyncPlan.Action.DROP, plan.single().action)
    }

    @Test
    fun `同 id 双重意图按各自意图独立判定`() {
        // enqueue 层已保证同 id 只留最新意图；plan 对重复 id 也安全（各自独立判定，互不影响）
        val plan = FavouriteSyncPlan.plan(
            cloudIds = emptySet(),
            pending = listOf(op("1", add = true), op("1", add = false)),
        )
        assertEquals(2, plan.size)
        assertEquals(FavouriteSyncPlan.Action.TOGGLE_ADD, plan[0].action)
        assertEquals(FavouriteSyncPlan.Action.DROP, plan[1].action)
    }
}
