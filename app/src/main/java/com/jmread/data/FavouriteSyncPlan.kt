package com.jmread.data

import kotlinx.serialization.Serializable

/**
 * 收藏同步的对账决策（纯函数，JVM 单测覆盖：docs/unified-library-design.md §4.2）。
 *
 * POST /favorite 是翻转端点、非幂等——队列里存的必须是「意图」而不是「待重放请求」，
 * flush 前先拿云端收藏 id 集合作真相，只对仍然有差异的条目补一次翻转；
 * 云端已符合意图的条目直接出队（可能在官方 App 里已完成），零请求、零风险。
 */
object FavouriteSyncPlan {

    enum class Action {
        /** 云端未收藏而意图是收藏 → 补一次翻转（期望翻转后为收藏态） */
        TOGGLE_ADD,
        /** 云端已收藏而意图是取消 → 补一次翻转（期望翻转后为未收藏态） */
        TOGGLE_REMOVE,
        /** 云端已符合意图 → 直接出队 */
        DROP,
    }

    data class Step(val comicId: String, val action: Action)

    fun plan(cloudIds: Set<String>, pending: List<FavouriteSyncQueue.PendingOp>): List<Step> = pending.map { op ->
        val inCloud = op.comicId in cloudIds
        Step(
            comicId = op.comicId,
            action = when {
                op.add && !inCloud -> Action.TOGGLE_ADD
                !op.add && inCloud -> Action.TOGGLE_REMOVE
                else -> Action.DROP
            },
        )
    }
}
