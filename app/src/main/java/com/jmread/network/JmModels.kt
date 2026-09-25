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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

/**
 * 「数字或字符串数字」字段的 Int 序列化器。
 *
 * 实测（2026-09-25）：/favorite 的 total 是字符串 "26"，而搜索/榜单的 total 是裸数字。
 * 声明 Int 时字符串形态会让整包解析失败——收藏列表「永远为空」就是这类坑。
 * 空串 / 非数字回退 0。
 */
object JmFlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.jmread.JmFlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return when (val el = input.decodeJsonElement()) {
            is JsonPrimitive -> el.content.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
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
     * 部分端点的列表键叫 list 而不是 content（实测 /week/filter、/favorite、/watch_list）。
     *
     * ⚠ 两个键都带 `= emptyList()` 默认值，所以键名写错时 kotlinx **不会抛异常**，
     *   只会静默给出空列表——这是本项目最隐蔽的一类故障（每周必看某期曾因此永远显示空）。
     *   读取方一律用 [items]，不要直接取 content。
     */
    val list: List<JmAlbumSummary> = emptyList(),
    /**
     * 浏览流恒为 10000（=125页×80，页数可信值）；搜索/榜单是裸数字；
     * /favorite 是字符串 "26"（2026-09-25 实测）→ 用灵活 Int 序列化器统一。
     */
    @Serializable(with = JmFlexibleIntSerializer::class)
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
    /**
     * 收藏列表项（/favorite）专属：最近一次更新的章节信息。
     * 线上形态未完全钉死（null / 对象 / 字符串都有可能）→ 用 JsonElement 接，
     * 读取方走 [latestEpText] / [latestEpAidText]，绝不让形态漂移炸整包。
     */
    @SerialName("latest_ep") val latestEp: JsonElement? = null,
    @SerialName("latest_ep_aid") val latestEpAid: JsonElement? = null,
) {
    /** 更新章节的展示文本：字符串原样 / 对象取 name 字段（null / 无内容 → 空串） */
    val latestEpText: String
        get() = when (val el = latestEp) {
            is JsonPrimitive -> if (el is JsonNull) "" else el.content
            is kotlinx.serialization.json.JsonObject ->
                (el["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            else -> ""
        }

    /** 更新章节的 photo id：字符串/数字原样 / 对象取 id 字段（null / 无内容 → 空串） */
    val latestEpAidText: String
        get() = when (val el = latestEpAid) {
            is JsonPrimitive -> if (el is JsonNull) "" else el.content
            is kotlinx.serialization.json.JsonObject ->
                (el["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            else -> ""
        }
}

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

// ---------- 动作 / 签到（需登录，2026-09-25 实测形态） ----------

@Serializable
data class JmActionResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmActionData? = null,
) : JmEnvelope

/**
 * 动作响应。实测两种来源：
 *  - POST /favorite {aid}（翻转开关）→ {"status":"ok","msg":"漫画添加到您最喜爱的清单!","type":"add"}
 *    第二次调用同端点返回 {"type":"remove","msg":"已移除收藏"} —— **type 是机器可读的翻转结果**
 *  - POST /daily_chk {user_id, daily_id} → {"msg":"Jcoin:40 EXP:100"}
 */
@Serializable
data class JmActionData(
    val status: String? = null,
    val msg: String? = null,
    /** 收藏开关结果："add" / "remove" */
    val type: String? = null,
    val aid: String? = null,
    val cid: String? = null,
    val spoiler: String? = null,
) {
    /** 收藏翻转语义结果：true=本次调用后为已收藏；null=响应未携带 type */
    val isFavouriteAfter: Boolean?
        get() = when (type) {
            "add" -> true
            "remove" -> false
            else -> null
        }
}

/**
 * GET /daily?user_id={uid} 的月历签到对象（实测 2026-09-25，活动「9月-兔兔月」）。
 * 不带 user_id 时该端点返回 data=[]（空数组）——「未登录/缺参」与「未签到」据此区分。
 */
@Serializable
data class JmDailyCalendarResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmDailyCalendar? = null,
) : JmEnvelope

@Serializable
data class JmDailyCalendar(
    /** 当月活动 id（POST /daily_chk 的必传参数） */
    @Serializable(with = JmFlexibleStringSerializer::class)
    @SerialName("daily_id") val dailyId: String = "",
    @SerialName("event_name") val eventName: String = "",
    /** 当前进度，如 "0%" / "14.3%" */
    @SerialName("currentProgress") val currentProgress: String = "",
    /** 月历矩阵：外层是周行，内层是 {date, signed} 天 */
    val record: List<List<JmDailyDay>> = emptyList(),
    @SerialName("three_days_coin") val threeDaysCoin: String = "",
    @SerialName("three_days_exp") val threeDaysExp: String = "",
    @SerialName("seven_days_coin") val sevenDaysCoin: String = "",
    @SerialName("seven_days_exp") val sevenDaysExp: String = "",
    @SerialName("background_phone") val backgroundPhone: String = "",
) {
    /** 当月已签天数（record 里的 signed 计数） */
    val signedDays: Int get() = record.sumOf { row -> row.count { it.signed } }
}

@Serializable
data class JmDailyDay(
    /** 日期（"01".."31"），线上偶发裸数字 → 灵活字符串 */
    @Serializable(with = JmFlexibleStringSerializer::class)
    val date: String = "",
    val signed: Boolean = false,
    /** 线上还有奖励/补签等附加键，忽略 */
)

// ---------- /promote 推荐本本（官方 App 首页频道，2026-09-25 实测） ----------

@Serializable
data class JmPromoteResponse(
    override val code: Int = 0,
    override val errorMsg: String? = null,
    val data: JmPromoteData = JmPromoteData(),
) : JmEnvelope

/**
 * /promote?page= 的频道 block 列表。
 * ⚠ 实测内容会轮换：首测 8 个 block，复测两次返回空列表 —— 调用方必须容空（best-effort）。
 */
@Serializable
data class JmPromoteData(
    val list: List<JmPromoteBlock> = emptyList(),
)

@Serializable
data class JmPromoteBlock(
    val id: String = "",
    val title: String = "",
    val slug: String = "",
    val type: String = "",
    /** 频道过滤值（如 "26"），语义未定，仅透传 */
    @SerialName("filter_val") val filterVal: String = "",
    val content: List<JmAlbumSummary> = emptyList(),
)
