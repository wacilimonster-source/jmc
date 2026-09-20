package com.jmread.core.model

import kotlinx.serialization.Serializable

/**
 * 领域模型：UI 只消费这些类型，不接触 DTO。
 *
 * 单源 App：作品 id 天然唯一（禁漫纯数字），不再有源命名空间——
 * ref 与 id 同值，保留 ref 属性名只为让路由/记账层调用点少改一行。
 */

/** 列表项（卡片） */
@Serializable
data class ComicSummary(
    val id: String,
    val title: String,
    /** 多作者以 " / " 连接 */
    val author: String = "",
    val coverUrl: String? = null,
    val finished: Boolean = false,
    val totalViews: Long = 0,
    val totalLikes: Long = 0,
    /** 标签（用于客户端标签筛选；列表接口不返回时为空） */
    val tags: List<String> = emptyList(),
    /** 更新时间（"yyyy-MM-dd..." ISO 前缀，用于日期范围筛选；源不支持时为空） */
    val updatedAt: String = "",
    /** 服务端收藏态（读侧回填，无需写权限） */
    val isFavourite: Boolean = false,
    /** 大分类名（category.title，列表卡片角标用） */
    val categoryName: String = "",
) {
    /** 作品标识：列表点击、路由参数、本地记账都用它（单源 == id） */
    val ref: String get() = id
}

/** 分类 */
data class ComicCategory(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    /** 真实存量（如「同人志 34.9 万」），禁漫独有 */
    val totalCount: Int = 0,
    /** 二级分类（slug + name） */
    val subCategories: List<ComicSubCategory> = emptyList(),
)

data class ComicSubCategory(
    val slug: String,
    val name: String,
)

/** 详情页 */
data class ComicDetail(
    val id: String,
    val title: String,
    /** 多作者以 " / " 连接展示；跳作者搜索时用 authors 拆分 */
    val author: String,
    val authors: List<String> = emptyList(),
    val description: String,
    val coverUrl: String?,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 作品系列（如原作名），可跳作品维度搜索 */
    val works: List<String> = emptyList(),
    /** 登场角色/演员，可跳角色维度搜索 */
    val actors: List<String> = emptyList(),
    val finished: Boolean = false,
    val pagesCount: Int = 0,
    val epsCount: Int = 0,
    val totalViews: Long = 0,
    val totalLikes: Long = 0,
    val commentsCount: Long = 0,
    val updatedAt: String = "",
    val createdAt: String = "",
    /** 云端收藏态初值（来自 is_favorite，读侧回填） */
    val isFavourite: Boolean = false,
    /** 付费本：price 非空 → 详情提示「需在禁漫 App 内获取」 */
    val isPaid: Boolean = false,
) {
    /** 作品标识：阅读器路由、本地进度记账用（单源 == id） */
    val ref: String get() = id
}

/** 章节 */
data class ComicChapter(
    val id: String,
    val title: String,
    val order: Int,
)

/** 阅读页 */
data class ComicPage(
    val index: Int,
    val imageUrl: String,
)

/** 分页结果 */
data class PageResult<T>(
    val items: List<T>,
    val page: Int = 1,
    val pages: Int = 1,
    /** 真实命中数（搜索/榜单）；浏览流为 0（total 恒 10000 不可信） */
    val total: Int = 0,
)

/**
 * 统一翻页判据（PiKA D2 修复）。
 * 浏览流 total 恒为 10000（=125页×80，第 126 页起服务端重发第 125 页）→ hardCap=125；
 * 搜索/榜单/评论 total 是真实命中数 → 直接 ceil；
 * total 不可信时退回「当页不满按完页处理」。
 */
fun pagesOf(total: Int, itemCount: Int, page: Int, pageSize: Int, hardCap: Int = Int.MAX_VALUE): Int {
    val isRealTotal = total > 0 && total != BROWSE_TOTAL_SENTINEL
    return if (isRealTotal) {
        minOf(ceilDiv(total, pageSize), hardCap)
    } else if (itemCount < pageSize) {
        minOf(page, hardCap)
    } else {
        minOf(page + 1, hardCap)
    }
}

/** 浏览流 total 恒为 10000 的实测哨兵值 */
const val BROWSE_TOTAL_SENTINEL = 10000

/** 浏览流硬上限：125 页（超过后服务端原样重发第 125 页） */
const val BROWSE_HARD_CAP = 125

fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

/** 排序方式 */
enum class ComicSort(val label: String) {
    DD("新到旧"),
    DA("旧到新"),
    LD("最多喜欢"),
    VD("最多观看"),
}

/** 按排序方式对已加载列表重排（多词交集等本地聚合场景） */
fun List<ComicSummary>.sortedByComicSort(sort: ComicSort): List<ComicSummary> = when (sort) {
    ComicSort.DD -> sortedByDescending { it.updatedAt }
    ComicSort.DA -> sortedBy { it.updatedAt }
    ComicSort.LD -> sortedByDescending { it.totalLikes }
    ComicSort.VD -> sortedByDescending { it.totalViews }
}

/** 连载状态筛选（禁漫不支持，能力位为 false 时 UI 不出现） */
enum class ComicStatus(val label: String) {
    ALL("全部"),
    FINISHED("已完结"),
    ONGOING("连载中"),
}

/** 用户（评论者） */
data class ComicUser(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val level: Int = 0,
    val exp: Int = 0,
    /** 等级名，如 "Lv.12" */
    val levelName: String = "",
    val title: String = "",
    val slogan: String = "",
    val email: String = "",
    val gender: String = "",
    val birthday: String = "",
    val characters: List<String> = emptyList(),
    val createdAt: String = "",
)

/** 漫画评论（禁漫 /forum 形态：子评论内嵌，content 为已剥离的纯文本） */
data class ComicComment(
    /** 禁漫真实主键 CID */
    val id: String,
    /** 已剥离 HTML 标签的正文 */
    val content: String,
    val user: ComicUser? = null,
    val createdAt: String = "",
    val likesCount: Int = 0,
    val isLiked: Boolean = false,
    val commentsCount: Int = 0,
    val isTop: Boolean = false,
    /** 内嵌子评论（/forum 自带 replys，无需新端点） */
    val replies: List<ComicComment> = emptyList(),
    /** 剧透 → UI 折叠展示 */
    val isSpoiler: Boolean = false,
    /** 导流广告（外链/网盘/破解关键词）→ UI 灰显折叠 */
    val isAd: Boolean = false,
    /** 等级徽章（expinfo.badges） */
    val badges: List<String> = emptyList(),
)

/** 每日签到结果 */
data class DailyCheckIn(
    val checkedIn: Boolean = false,
    val consecutiveDays: Int = 0,
    val message: String = "",
)

/** 每周必看期数 */
data class WeekPeriod(
    val id: String,
    /** 期号标签（time 字段） */
    val label: String,
)
