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
     */
    suspend fun refreshSetting(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSettingFetch < SETTING_REFRESH_INTERVAL) return
        try {
            val resp = json.decodeFromString(
                JmSettingResponse.serializer(),
                execute("/setting"),
            )
            lastSettingFetch = System.currentTimeMillis()
            val d = resp.data
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

    /** 登录：POST /login（form: username + password）；返回会话密钥 s */
    suspend fun login(email: String, password: String): String {
        val text = execute("/login", mapOf("username" to email, "password" to password))
        val resp = json.decodeFromString(JmLoginResponse.serializer(), text)
        if (resp.data.s.isBlank()) throw JmException("禁漫登录失败：${resp.errorMsg ?: text.take(120)}")
        return resp.data.s
    }

    /** 退出登录：GET /logout（尽力而为，失败不抛） */
    suspend fun logout() {
        runCatching { execute("/logout") }
    }

    // ---------- 需登录端点（能力未验证，UI 层不渲染入口；方法保留供验证后启用） ----------

    /** 收藏列表：GET /favorite?page=&folder_id=0&o=mr */
    suspend fun favorites(page: Int): JmListResponse =
        json.decodeFromString(execute("/favorite?page=$page&folder_id=0&o=mr"))

    /**
     * 收藏 / 取消收藏切换。
     * ⚠ 参数未验证（取消字段官方移动端未公开，type=1/0 是社区猜测），
     * 能力位 hasCloudFavouriteWrite=false 期间 UI 不得调用。
     */
    suspend fun favoriteToggle(aid: String, add: Boolean): Boolean {
        val type = if (add) "1" else "0"
        val text = execute("/favorite?aid=${URLEncoder.encode(aid, "UTF-8")}&type=$type")
        return json.decodeFromString(JmActionResponse.serializer(), text).code == 200
    }

    /** 签到状态：GET /daily（免登录 data=null → 是「未登录」不是「未签到」） */
    suspend fun dailyStatus(): JmDailyResponse =
        json.decodeFromString(execute("/daily"))

    /** 执行签到：GET /daily_chk */
    suspend fun dailyCheckIn(): JmDailyResponse =
        json.decodeFromString(execute("/daily_chk"))

    /** 云端浏览历史：GET /watch_list?page= */
    suspend fun watchList(page: Int): JmListResponse =
        json.decodeFromString(execute("/watch_list?page=$page"))

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
