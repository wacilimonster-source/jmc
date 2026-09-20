package com.jmread.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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

/**
 * 「数字或字符串」字段的通用序列化器。
 *
 * 禁漫线上**同一字段会在不同条目里给不同类型**，2026-09-21 实测：
 *   /categories.categories[].id   首条 `最新A漫` 是数字 0，其余是字符串 "1"
 *   /chapter.data.id              恒为裸数字 646603
 * kotlinx 对「数字 -> String」是**抛异常**（不是自动转字符串），
 * 所以这两个字段声明成 String 就会让整包解析失败：
 *   /categories 失败被 JmRepository.categories() 的 runCatching 吞掉 -> 分类列表静默变空
 *   /chapter 失败 -> 阅读器打不开任何一章
 *
 * 语义依据见 app/src/test/.../KotlinxCoercionSemanticsTest.kt（勿凭印象改）。
 */
object JmFlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.jmread.JmFlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val el = input.decodeJsonElement()) {
            is JsonPrimitive -> el.content
            else -> el.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
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
    /** 线上形如 "https://cdn-msp3.jmapiproxy1.cc"（带 scheme）；DomainPool 消费时会剥掉 */
    @SerialName("img_host") val imgHost: String = "",
    @SerialName("jm3_version") val jm3Version: String = "",
    /**
     * 线上实测是**对象数组** `[{title:"图源1",key:1}, ...]`，不是字符串数组。
     *
     * 2026-09-21 真机事故：本字段曾声明为 `List<String>`，导致 /setting 整体解析抛
     * `Expected beginning of the string, but got '{'`——而 /setting 是「域名四级自愈」
     * 的唯一数据源，解析失败等于自愈链路整体失效（且失败静默，只在调试日志留一行 WARN）。
     * 桌面单测没抓到，是因为 fixture 里这条是手写的字符串数组（形状就是错的）。
     */
    @SerialName("app_shunts") val appShunts: List<JmShunt> = emptyList(),
)

/** /setting.app_shunts 的元素：图源切换项（当前 App 未消费，仅按线上形态声明） */
@Serializable
data class JmShunt(
    val title: String = "",
    val key: Int = 0,
)

/**
 * /setting 的宽松兜底解析：跳过 DTO，直接按字段名取值。
 *
 * 分工：严格 DTO 负责让类型漂移在测试期变红灯；本兜底负责让自愈链路在真机上活下来。
 * /setting 是「域名四级自愈」的唯一数据源，不能因为单个字段的类型漂移整体失效
 * （2026-09-21 事故：app_shunts 由字符串数组变对象数组，自愈链路静默瘫痪）。
 *
 * 返回 null 表示连 data 对象都取不到（彻底失败），交由上层按失败处理。
 */
internal fun parseSettingLeniently(text: String): JmSettingData? {
    val data = runCatching {
        Json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
    }.getOrNull() ?: return null
    fun str(key: String): String =
        (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return JmSettingData(
        baseUrl = str("base_url"),
        cnBaseUrl = str("cn_base_url"),
        mainWebHost = str("main_web_host"),
        imgHost = str("img_host"),
        jm3Version = str("jm3_version"),
    ).takeIf { it.baseUrl.isNotBlank() || it.imgHost.isNotBlank() || it.jm3Version.isNotBlank() }
}

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
    /** 线上首条 `最新A漫` 是数字 0、其余是字符串 "1"，必须用宽松序列化器 */
    @Serializable(with = JmFlexibleStringSerializer::class) val id: String = "",
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
    /**
     * 部分端点的列表键叫 list 而不是 content（实测 /week/filter）。
     *
     * ⚠ 两个键都带 `= emptyList()` 默认值，所以键名写错时 kotlinx **不会抛异常**，
     *   只会静默给出空列表——这是本项目最隐蔽的一类故障（每周必看某期曾因此永远显示空）。
     *   读取方一律用 [items]，不要直接取 content。
     */
    val list: List<JmAlbumSummary> = emptyList(),
    /** 浏览流恒为 10000（=125页×80，页数可信值）；搜索/榜单/收藏是真实命中数 */
    val total: Int = 0,
) {
    /** 统一取值口：content 与 list 哪个非空用哪个 */
    val items: List<JmAlbumSummary> get() = if (content.isNotEmpty()) content else list
}

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

/**
 * 分类名对象（列表项的 category / category_sub）。
 *
 * ⚠ 线上这两个字段的值**可能是 null**（2026-09-21 实测：浏览流 23 条、搜索 20 条的
 *   category_sub 都是 `{id:null, title:null}`）。
 *   如果按非空 String 声明，能跑通就完全依赖 `Json { coerceInputValues = true }`
 *   把 null 强转成默认值 —— 那等于把「类型漂移要在测试期就红」的防线（ADR-6）让掉了。
 *   这里显式声明可空，读取方统一走 [idText] / [titleText]，不依赖宽容解析。
 */
@Serializable
data class JmNamedItem(
    val id: String? = null,
    val title: String? = null,
) {
    /** 展示/匹配用的大分类名，null 回退空串 */
    val titleText: String get() = title.orEmpty()
    val idText: String get() = id.orEmpty()
}

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
    /** 线上是裸数字（实测 646603），声明 String 会整包解析失败 -> 阅读器打不开章节 */
    @Serializable(with = JmFlexibleStringSerializer::class) val id: String = "",
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
    /**
     * 线上是**对象数组** `[{content:"/static/...png", name:"小林", id:"171"}, ...]`，
     * 不是字符串数组（2026-09-21 实测；声明 List<String> 会让整个 /forum 解析失败）。
     */
    val badges: List<JmBadge> = emptyList(),
)

/** /forum.expinfo.badges 的元素：等级徽章（当前 App 未渲染，仅按线上形态声明） */
@Serializable
data class JmBadge(
    /** 徽章图相对路径（如 /static/resources/images/...png），非展示文案 */
    val content: String = "",
    /** 徽章展示名（如 "小林"、"托尔"） */
    val name: String = "",
    val id: String = "",
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
