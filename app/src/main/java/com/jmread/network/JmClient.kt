package com.jmread.network

import com.jmread.core.log.LogStore
import com.jmread.core.model.ComicSort
import com.jmread.core.netconfig.DomainPool
import com.jmread.data.SourcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 禁漫移动端 API 客户端。
 *
 * 相对 PiKA 版本的关键升级（依据 2026-09-19 实测调研 D9/D10/D11）：
 *  - 失败换域重试引擎（范式来自 PicaClient，禁漫无限流冷却）：IOException / 非 2xx 自动换
 *    下一个镜像重试；响应首字符非 '{' 视为服务端异常同样触发换域
 *  - JmException 携带 httpCode，会话失效走结构化判断
 *  - 版本号由 /setting.jm3_version 动态刷新；域名池吃 /setting 下发的 base_url / img_host
 *  - 内存 CookieJar：/setting 的初始 Set-Cookie 全程携带（社区库实测缺 cookie 会被
 *    跳转「禁漫娘」页），AVS 会话仍由显式 Cookie 头管理
 */
object JmClient {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        // 不用 isLenient：字段类型漂移要在测试期就红，而不是靠宽容解析掩盖（PiKA D1 教训）
    }

    /** 签名/解密密钥与域名的防风控一致性：版本号动态读取，见 JmCrypto.appVersion */
    private val client: OkHttpClient by lazy {
        if (!BcTls.isAvailable()) BcTls.install()
        BcTls.applyTo(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .cookieJar(InlineCookieJar)
        ).build()
    }

    private object InlineCookieJar : CookieJar {
        private val store = HashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(store) { store[url.host] = cookies }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store[url.host].orEmpty() }
    }

    /** 会话失效钩子（401 时静默重登并重试一次） */
    var onUnauthorizedHook: (suspend () -> Unit)? = null

    private val headers: Map<String, String> get() = buildMap {
        put("device", "ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3")
        put("os-version", "9.0")
        put("platform", "ANDROID")
        put("app-version", "3.4.17")
        put("channel", "app")
        put("User-Agent", "okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0")
    }

    /** 单次调用的最大换域尝试数（覆盖内置镜像数即可） */
    private const val MAX_HOST_ATTEMPTS = 4

    /**
     * 该 HTTP 状态是否值得「换一条线路再试」。
     *
     * 5xx = 服务端/网关故障，换域有效（实测 /search 在 4 个镜像上随机 500、同时必有镜像 200）。
     * 429 = 限流，换域同样有效。
     * 其余 4xx = 业务错误（端点不存在、参数非法、未登录），换域改变不了结果。
     *
     * ⚠ 早期版本把所有非 2xx 都当业务错误直接上抛，导致 5xx 时不换域——
     *   等于把「换条线路就好」变成硬失败。
     */
    internal fun isRetryableHttpStatus(code: Int): Boolean = code >= 500 || code == 429

    /** 统一请求：带签名头 + 解密响应 + 失败换域 + 401 重登 */
    private suspend fun execute(
        relative: String,
        form: Map<String, String>? = null,
        retriedAuth: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(MAX_HOST_ATTEMPTS) { attempt ->
            val host = DomainPool.currentApiHost
            val sign = JmCrypto.sign()
            val builder = Request.Builder()
                .url("https://$host" + relative)
                .header("token", sign.token)
                .header("tokenparam", sign.tokenparam)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val session = SourcePrefs.current().jmToken
            if (!session.isNullOrEmpty()) builder.header("Cookie", "AVS=$session")

            if (form != null) {
                val body = form.map { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }
                    .joinToString("&")
                builder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            } else {
                builder.get()
            }

            try {
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.code == 401 && !retriedAuth) {
                        // 会话失效：静默重登后重试一次（结构性判断，不靠文案）
                        onUnauthorizedHook?.invoke()
                        return@withContext execute(relative, form, retriedAuth = true)
                    }
                    if (resp.code == 401) throw JmException("禁漫接口 401：${text.take(120)}", 401)
                    if (isRetryableHttpStatus(resp.code)) {
                        // 5xx / 429 是服务端或网关故障：换一条线路大概率能成。
                        // 实测 2026-09-21：/search 在 4 个内置镜像上随机返回 HTTP 500（空体），
                        // 同一时刻总有镜像返回 200 —— 不换域就等于把「换条线路就好」变成硬失败。
                        throw IOException("禁漫接口 ${resp.code}（服务端故障，换域重试）：${text.take(80)}")
                    }
                    if (!resp.isSuccessful) {
                        throw JmException("禁漫接口 ${resp.code}: ${text.take(120)}", resp.code)
                    }
                    if (text.trimStart().firstOrNull() != '{') {
                        // 网关异常页 / 风控页等非 JSON 响应：当作该 host 故障处理
                        throw IOException("响应非 JSON（host=$host）：${text.take(60)}")
                    }
                    DomainPool.markGood(host)
                    return@withContext decryptEnvelope(text, sign.ts)
                }
            } catch (e: JmException) {
                // 业务异常（401/4xx）：换域无意义，直接上抛
                throw e
            } catch (e: Exception) {
                lastError = e
                LogStore.log("jm-net", "WARN", "host=$host 失败(${e.message?.take(80)})，换下一条线路")
                DomainPool.markBad(host)
                DomainPool.rotate()
                // 继续下一次尝试
            }
        }
        throw lastError ?: JmException("禁漫接口不可用：所有线路均失败")
    }

    /** 把加密的 data 字段解密后替换回原 JSON，再返回完整可解析文本 */
    private fun decryptEnvelope(text: String, ts: Long): String {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text

        // 端点不存在 / 已下线时，服务端返回的是 HTTP 200 + code 200 + data=[] + errorMsg="Not legal.xxx"。
        // 不检查 errorMsg 的话，这类响应会被当成正常响应送进 DTO，静默变成空数据 ——
        // 表现是「点了没反应」而不是报错，排查成本极高（实测 /random /hot_search /tags /user 都是这个形态）。
        root["errorMsg"]?.let { el ->
            val msg = (el as? JsonPrimitive)?.contentOrNull
            if (msg != null && msg.startsWith("Not legal", ignoreCase = true)) {
                throw JmException("禁漫端点不存在或已下线：$msg")
            }
        }

        val dataEl = root["data"]
        if (dataEl !is JsonPrimitive || !dataEl.isString) return text
        val plain = runCatching { JmCrypto.decrypt(dataEl.content, ts) }
            .getOrElse { throw JmException("禁漫响应解密失败（密钥/时间戳可能漂移）：${it.message}") }
        val newData = runCatching { Json.parseToJsonElement(plain) }
            .getOrElse { throw JmException("禁漫解密结果非法：${it.message}") }
        val newRoot = buildJsonObject {
            root.forEach { (k, v) -> put(k, v) }
            put("data", newData)
        }
        return json.encodeToString(JsonObject.serializer(), newRoot)
    }

    // ---------- /setting：域名/版本/img_host 自愈 ----------

    private var lastSettingFetch = 0L
    private const val SETTING_REFRESH_INTERVAL = 6 * 60 * 60 * 1000L

    /**
     * 拉 /setting 刷新域名池 / 图片 host / 版本号。
     * 在启动与每次换域成功后触发；失败静默（内置表兜底）。
     *
     * 严格 DTO 解析失败时降级到 [extractSettingLeniently]：
     * /setting 是「域名四级自愈」的唯一数据源，不能因为单个字段的类型漂移整体失效。
     * 2026-09-21 真机事故即为此：app_shunts 线上是对象数组、DTO 声明成 List<String>，
     * 导致自愈链路静默瘫痪（只在调试日志留一行 WARN）。
     */
    suspend fun refreshSetting(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSettingFetch < SETTING_REFRESH_INTERVAL) return
        try {
            val text = execute("/setting")
            val parsed = runCatching { json.decodeFromString(JmSettingResponse.serializer(), text) }
            val d = parsed.getOrNull()?.data
                ?: parseSettingLeniently(text)?.also {
                    LogStore.log(
                        "jm-net", "WARN",
                        "/setting DTO 解析失败，已降级宽松提取（自愈字段仍生效）：" +
                            parsed.exceptionOrNull()?.message?.take(70),
                    )
                }
                ?: error("/setting 响应无法解析（严格 + 宽松均失败）")
            lastSettingFetch = System.currentTimeMillis()
            DomainPool.applySetting(d.baseUrl.takeIf { it.isNotBlank() }, d.imgHost.takeIf { it.isNotBlank() }, null)
            val prefs = SourcePrefs.current()
            if (d.baseUrl.isNotBlank()) prefs.setResolvedBase(d.baseUrl)
            if (d.imgHost.isNotBlank()) prefs.setResolvedImgHost(d.imgHost)
            if (d.jm3Version.isNotBlank()) prefs.setJmVersion(d.jm3Version)
        } catch (e: JmException) {
            throw e
        } catch (e: Exception) {
            // /setting 失败不影响主流程：内置域名表兜底
            LogStore.log("jm-net", "WARN", "/setting 刷新失败：${e.message?.take(80)}")
        }
    }

    // ---------- 免登录端点 ----------

    suspend fun categories(): JmCategoriesResponse =
        json.decodeFromString(execute("/categories"))

    /** 浏览 / 分类流：/categories/filter */
    suspend fun browse(page: Int, category: String?, sort: ComicSort): JmListResponse {
        val q = buildString {
            append("/categories/filter?page=").append(page)
            append("&t=a")
            if (!category.isNullOrBlank()) append("&c=").append(URLEncoder.encode(category, "UTF-8"))
        }
        return fetchList(q, sort)
    }

    /** 排行榜：/categories/filter 配 o=mv_t/mv_w/mv_m（total 为真实条数） */
    suspend fun rankList(order: String, page: Int = 1): JmListResponse =
        json.decodeFromString(execute("/categories/filter?page=$page&o=$order&t=a"))

    /**
     * 搜索：main_tag 0 综合 / 1 作品 / 2 作者 / 3 标签（实测各有独立结果与真实 total）。
     * c= 分类过滤实测生效。
     */
    suspend fun search(
        keyword: String,
        page: Int,
        sort: ComicSort,
        mainTag: Int = 0,
        category: String? = null,
    ): JmListResponse {
        val q = buildString {
            append("/search?search_query=").append(URLEncoder.encode(keyword, "UTF-8"))
            append("&page=").append(page)
            append("&main_tag=").append(mainTag)
            append("&t=a")
            if (!category.isNullOrBlank()) append("&c=").append(URLEncoder.encode(category, "UTF-8"))
        }
        return fetchList(q, sort)
    }

    /** 详情：/album?id=（扁平结构，series 即章节列表） */
    suspend fun album(albumId: String): JmAlbumResponse =
        json.decodeFromString(execute("/album?id=${URLEncoder.encode(albumId, "UTF-8")}"))

    /** 章节图片：/chapter?id={photoId} 一次返回整章 images 列表 */
    suspend fun chapter(photoId: String): JmChapterResponse =
        json.decodeFromString(execute("/chapter?id=${URLEncoder.encode(photoId, "UTF-8")}"))

    /** 评论：/forum?mode=all&page=&aid=（主键 CID，子评论内嵌 replys） */
    suspend fun comments(albumId: String, page: Int): JmForumResponse =
        json.decodeFromString(execute("/forum?mode=all&page=$page&aid=${URLEncoder.encode(albumId, "UTF-8")}"))

    /** 每周必看期数列表：/week */
    suspend fun week(): JmWeekResponse =
        json.decodeFromString(execute("/week"))

    /** 每周必看某期内容流：/week/filter?id={期 id}（参数形态实现期以探测脚本核实） */
    suspend fun weekFilter(weekId: String, page: Int): JmListResponse =
        fetchList("/week/filter?id=${URLEncoder.encode(weekId, "UTF-8")}&page=$page&t=a", ComicSort.DD)

    /** 登录：POST /login（form: username + password）；返回 data（s=会话, uid=用户 id） */
    suspend fun login(email: String, password: String): JmLoginData {
        val text = execute("/login", mapOf("username" to email, "password" to password))
        val resp = json.decodeFromString(JmLoginResponse.serializer(), text)
        if (resp.data.s.isBlank()) throw JmException("禁漫登录失败：${resp.errorMsg ?: text.take(120)}")
        return resp.data
    }

    /** 退出登录：GET /logout（尽力而为，失败不抛） */
    suspend fun logout() {
        runCatching { execute("/logout") }
    }

    // ---------- 需登录端点（2026-09-25 实测定案，见 reports/feature-design-20260925.html） ----------

    /**
     * 收藏列表：GET /favorite?page=&folder_id=0&o=mr（o=mp 为按更新时间）。
     * data 键是 list（JmListData.items 兼容），total 是字符串数字（JmFlexibleInt 兼容），
     * 列表项自带 latest_ep / latest_ep_aid —— 「有更新」角标的数据源。
     */
    suspend fun favorites(page: Int, orderBy: String = "mr"): JmListResponse =
        json.decodeFromString(execute("/favorite?page=$page&folder_id=0&o=$orderBy"))

    /**
     * 收藏 / 取消收藏：POST /favorite {aid} —— 同一端点的翻转开关（实测两次调用
     * 依次返回 type=add / type=remove）。调用方以响应 [JmActionData.isFavouriteAfter] 为准，
     * 不要本地乐观翻转（双击防抖也在这里面做）。
     */
    suspend fun favoriteToggle(aid: String): JmActionData? {
        val text = execute("/favorite", form = mapOf("aid" to aid))
        return json.decodeFromString(JmActionResponse.serializer(), text).data
    }

    /**
     * 月历签到数据：GET /daily?user_id={uid}。
     * ⚠ 不带 user_id 返回 data=[]（空数组）——实测 2026-09-25，uid 从登录 data.uid 取。
     */
    suspend fun dailyCalendar(userId: String): JmDailyCalendarResponse =
        json.decodeFromString(execute("/daily?user_id=${URLEncoder.encode(userId, "UTF-8")}"))

    /** 执行签到：POST /daily_chk {user_id, daily_id}；成功 data={"msg":"Jcoin:40 EXP:100"} */
    suspend fun dailyCheckIn(userId: String, dailyId: String): JmActionData? {
        val text = execute(
            "/daily_chk",
            form = mapOf("user_id" to userId, "daily_id" to dailyId),
        )
        return json.decodeFromString(JmActionResponse.serializer(), text).data
    }

    /** 云端浏览历史：GET /watch_list?page=（data 键是 list） */
    suspend fun watchList(page: Int): JmListResponse =
        json.decodeFromString(execute("/watch_list?page=$page"))

    /** 推荐本本频道 block（内容会轮换，调用方必须容空） */
    suspend fun promote(page: Int = 1): JmPromoteResponse =
        json.decodeFromString(execute("/promote?page=$page"))

    // ---------- 内部 ----------

    /**
     * 列表请求统一入口：附加排序参数。
     * 服务端无升序参数（DA），客户端倒序只对当页生效、跨页语义错误——
     * 能力声明已把 DA 从排序选项剔除，这里仅为兜底保留映射。
     */
    private suspend fun fetchList(q: String, sort: ComicSort): JmListResponse {
        val full = "$q&o=${sortToO(sort)}"
        val resp = json.decodeFromString<JmListResponse>(execute(full))
        return if (sort == ComicSort.DA) {
            resp.copy(data = resp.data.copy(content = resp.data.items.asReversed()))
        } else {
            resp
        }
    }

    private fun sortToO(sort: ComicSort): String = when (sort) {
        ComicSort.DD -> "mr"   // 最新
        ComicSort.DA -> "mr"   // 服务端无升序参数
        ComicSort.LD -> "tf"   // 最多喜欢
        ComicSort.VD -> "mv"   // 最多观看
    }
}
