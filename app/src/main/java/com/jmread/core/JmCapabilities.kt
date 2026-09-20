package com.jmread.core

import com.jmread.core.model.ComicSort

/**
 * 本源能力声明：UI 渲染开关的唯一事实来源。
 *
 * 产品原则「能力说真话」——源没有的功能，入口直接消失，而不是点了报错。
 * 所有取值来自 2026-09-19 线上实测（pika/reports/jm-source-design-20260919.html），
 * 不猜。写侧能力在真实账号验证之前一律 false。
 */
object JmCapabilities {

    // ---------- 排序 ----------
    /** 服务端仅支持 mr(最新)/mv(最多观看)/tf(最多喜欢)；无升序参数，DA 不暴露 */
    val supportedSorts: List<ComicSort> = listOf(ComicSort.DD, ComicSort.LD, ComicSort.VD)

    // ---------- 筛选 ----------
    /** /album 无 status 字段 → 完结/连载筛选不存在 */
    const val supportsStatusFilter = false
    /** t= 四种取值实测返回相同 → 时间筛选不存在 */
    const val supportsTimeFilter = false
    /** 汉化组 / 上传者筛选无移动端参数 */
    const val supportsTranslationGroupFilter = false
    const val supportsUploaderFilter = false
    /** c= 分类过滤实测生效 */
    const val supportsCategoryFilter = true
    /** main_tag 四维搜索（综合/作品/作者/标签）实测各有独立结果 */
    const val supportsDimensionSearch = true

    // ---------- 内容位 ----------
    const val hasBrowse = true
    const val hasRank = true
    const val hasWeeklyPicks = true
    const val hasTagWall = true
    const val hasRelated = true
    /** /random 不存在；o=md 是稳定序不是随机序 */
    const val hasRandom = false
    /** 无热词端点 */
    const val hasHotWords = false

    // ---------- 社区 ----------
    const val hasCommentRead = true
    /** 发评论/回复参数与风控未验证 */
    const val hasCommentWrite = false
    /** 子评论内嵌在 replys，无需新端点 */
    const val hasSubComment = true
    const val hasMyComments = false

    // ---------- 账号 ----------
    const val hasLogin = true
    const val hasRegister = false
    const val hasForgotPassword = false
    const val hasProfile = false
    const val hasProfileEdit = false
    /** 云端收藏列表端点存在但响应未验证；写侧参数未验证 */
    const val hasCloudFavouriteRead = false
    const val hasCloudFavouriteWrite = false
    /** 签到/云端历史：需账号验证后放开 */
    const val hasDailyCheckIn = false
    const val hasCloudHistory = false

    // ---------- 详情 ----------
    /** 列表/详情自带 is_favorite/liked，可读回填收藏态初值（无需写权限） */
    const val hasFavouriteStateRead = true
    /** 付费本 price/purchased 字段存在 */
    const val hasPaidNotice = true
}
