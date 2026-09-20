package com.jmread.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 禁漫移动端 API 响应模型。
 *
 * 字段类型全部按 2026-09-19 线上实测形态声明（pika/reports/jm-source-design-20260919.html E2），
 * 不依赖 isLenient——线上给对象就声明对象、给数组就声明数组、给裸数字就声明数字。
 * 服务端形态漂移时，JmDtoFixtureTest 会先红。
 */

/** 响应信封：code + errorMsg + data(解密后明文) */
interface JmEnvelope {
    val code: Int
    val errorMsg: String?
}

// ---------- /setting（data 是明文对象，不走解密分支） ----------

@Serializable
data class JmSettingResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmSettingData = JmSettingData(),
) : JmEnvelope

@Serializable
data class JmSettingData(
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("cn_base_url") val cnBaseUrl: String = "",
    @SerialName("main_web_host") val mainWebHost: String = "",
    @SerialName("img_host") val imgHost: String = "",
    @SerialName("jm3_version") val jm3Version: String = "",
    @SerialName("app_shunts") val appShunts: List<String> = emptyList(),
)

// ---------- /categories ----------

@Serializable
data class JmCategoriesResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmCategoriesData = JmCategoriesData(),
) : JmEnvelope

@Serializable
data class JmCategoriesData(
    val categories: List<JmCategory> = emptyList(),
    /** 官方标签组（4 组共 43 个标签）——标签墙的数据源 */
    val blocks: List<JmTagGroup> = emptyList(),
)

@Serializable
data class JmCategory(
    val id: String = "",
    val name: String = "",
    /** 用作筛选参数 c= 的取值（优先于 id） */
    val slug: String = "",
    @SerialName("total_albums") val totalAlbums: Int = 0,
    @SerialName("sub_categories") val subCategories: List<JmSubCategory> = emptyList(),
)

@Serializable
data class JmSubCategory(
    val name: String = "",
    val slug: String = "",
)

@Serializable
data class JmTagGroup(
    val title: String = "",
    val content: List<String> = emptyList(),
)

// ---------- 列表（浏览 / 搜索 / 排行 / 收藏 / 云端历史） ----------

@Serializable
data class JmListResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmListData = JmListData(),
) : JmEnvelope

@Serializable
data class JmListData(
    val content: List<JmAlbumSummary> = emptyList(),
    /** 浏览流恒为 10000（=125页×80，页数可信值）；搜索/榜单/收藏是真实命中数 */
    val total: Int = 0,
)

/** 列表项。注意：category / category_sub 是 {id,title} 对象，不是字符串 */
@Serializable
data class JmAlbumSummary(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val image: String = "",
    val category: JmNamedItem = JmNamedItem(),
    @SerialName("category_sub") val categorySub: JmNamedItem = JmNamedItem(),
    val liked: Boolean = false,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("update_at") val updateAt: Long = 0,
    /** 发布日期字符串，如 2026-08-28 */
    val adddate: String = "",
    /** 搜索结果额外返回的简介（可为 null） */
    val description: String? = null,
)

@Serializable
data class JmNamedItem(
    val id: String = "",
    val title: String = "",
)

// ---------- /album 详情 ----------

@Serializable
data class JmAlbumResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmAlbumDetail = JmAlbumDetail(),
) : JmEnvelope

@Serializable
data class JmAlbumDetail(
    /** 线上是未加引号的裸数字 */
    val id: Long = 0,
    val name: String = "",
    /** 线上是数组，多人协作常见（如 ["美娜讚","鋼鐵王","NTR"]） */
    val author: List<String> = emptyList(),
    /** 可为 null */
    val description: String? = null,
    /** 详情接口不返回图片，仅章节接口返回 */
    val images: List<String> = emptyList(),
    val addtime: String = "",
    /** 统计数字段线上是字符串数字 */
    @SerialName("total_views") val totalViews: String = "0",
    @SerialName("total_photos") val totalPhotos: Int = 0,
    val likes: String = "0",
    @SerialName("comment_total") val commentTotal: String = "0",
    /** 章节列表（单章本子为空，回退用 album id 自身作为 photo id） */
    val series: List<JmSeriesItem> = emptyList(),
    @SerialName("series_id") val seriesId: String = "",
    val tags: List<String> = emptyList(),
    val works: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    /** 付费相关：price 非空表示需在禁漫 App 内获取 */
    val price: String = "",
    val purchased: String = "",
    @SerialName("is_aids") val isAids: Boolean = false,
    /** 云端收藏态（读侧回填详情页心形初值，无需写权限） */
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    val liked: Boolean = false,
    @SerialName("related_list") val relatedList: List<JmRelatedItem> = emptyList(),
)

@Serializable
data class JmSeriesItem(
    val id: String = "",
    /** 可为空串 → 渲染回退「第 N 话」 */
    val name: String = "",
    /** 章节真实顺序按 sort 排，不是数组下标 */
    val sort: String = "0",
)

@Serializable
data class JmRelatedItem(
    val id: String = "",
    val name: String = "",
    val author: String = "",
)

// ---------- /chapter ----------

@Serializable
data class JmChapterResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmChapterData = JmChapterData(),
) : JmEnvelope

@Serializable
data class JmChapterData(
    val id: String = "",
    /** 图片文件名列表，如 ["00001.webp", "00002.webp"]（连续编号） */
    val images: List<String> = emptyList(),
)

// ---------- /forum 评论 ----------

@Serializable
data class JmForumResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmForumData = JmForumData(),
) : JmEnvelope

@Serializable
data class JmForumData(
    val list: List<JmForumComment> = emptyList(),
    val total: Int = 0,
)

/** 评论。真实主键是 CID（不是 id）；子评论内嵌在 replys；content 是 HTML */
@Serializable
data class JmForumComment(
    @SerialName("CID") val cid: String = "",
    @SerialName("AID") val aid: String = "",
    @SerialName("parent_CID") val parentCid: String = "0",
    val username: String = "",
    val nickname: String = "",
    val content: String = "",
    val addtime: String = "",
    val likes: String = "0",
    val photo: String = "",
    /** "1" = 剧透 */
    val spoiler: String = "0",
    val expinfo: JmExpInfo = JmExpInfo(),
    val replys: List<JmForumComment> = emptyList(),
)

/** 等级经验（等级徽章数据完整，可照哔咔等级条渲染） */
@Serializable
data class JmExpInfo(
    @SerialName("level_name") val levelName: String = "",
    val level: Int = 0,
    val exp: String = "0",
    val badges: List<String> = emptyList(),
)

// ---------- /week 每周必看 ----------

@Serializable
data class JmWeekResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmWeekData = JmWeekData(),
) : JmEnvelope

@Serializable
data class JmWeekData(
    val categories: List<JmWeekItem> = emptyList(),
)

@Serializable
data class JmWeekItem(
    val id: String = "",
    /** 线上 title 恒空，展示用期号 time */
    val time: String = "",
    val title: String = "",
)

// ---------- /login ----------

@Serializable
data class JmLoginResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmLoginData = JmLoginData(),
) : JmEnvelope

@Serializable
data class JmLoginData(
    /** 会话密钥，后续请求放入 Cookie: AVS={s} */
    val s: String = "",
    val uid: String = "",
)

// ---------- 动作 / 签到（需登录） ----------

@Serializable
data class JmActionResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmActionData? = null,
) : JmEnvelope

@Serializable
data class JmActionData(
    /** /favorite 返回的机器码动作描述（如 "加入收藏成功" / "取消收藏成功"） */
    val action: String? = null,
)

@Serializable
data class JmDailyResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    /** 免登录时 data=null：是「未登录」不是「未签到」 */
    val data: JmDailyData? = null,
) : JmEnvelope

@Serializable
data class JmDailyData(
    @SerialName("is_check_in") val isCheckIn: Boolean = false,
    @SerialName("check_in_days") val checkInDays: Int = 0,
    val days: Int = 0,
    val message: String = "",
)
