package com.jmread.mock

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 模拟响应路由：按 API path 分发到 fixture 模板。
 * fixture 从 debug assets 的 jm/ 目录读取（与 app/src/test/resources/jm 同源，
 * 由 build.gradle.kts 的 debug sourceSets.assets.srcDir 挂进来）。
 */
object MockResponses {

    private val cache = HashMap<String, String>()
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    private fun fixture(name: String): String {
        synchronized(cache) { cache[name]?.let { return it } }
        val ctx = context ?: error("MockResponses 未初始化")
        val text = ctx.assets.open("jm/$name.json").use { it.readBytes().decodeToString() }
        synchronized(cache) { cache[name] = text }
        return text
    }

    /**
     * @return 模拟响应体（JSON 文本），未识别的 path 返回 null（交还真实网络，通常不该发生）
     */
    fun serve(path: String, query: Map<String, String>, isPost: Boolean, postForm: Map<String, String>): String? {
        return when {
            path == "/setting" -> fixture("setting")
            path == "/categories" -> fixture("categories")
            path == "/categories/filter" -> MockTemplates.renderListPage(fixture("list"), pageOf(query))
            path == "/search" -> MockTemplates.renderListPage(fixture("search"), pageOf(query))
            path == "/album" -> MockTemplates.renderAlbum(fixture("album"), query["id"].orEmpty())
            path == "/chapter" -> MockTemplates.renderChapter(fixture("chapter"), query["id"].orEmpty())
            path == "/forum" -> MockTemplates.renderForum(fixture("forum"), pageOf(query))
            path == "/week" -> fixture("week")
            path == "/week/filter" -> MockTemplates.renderListPage(fixture("weekFilter"), pageOf(query))
            path == "/promote" -> promote(pageOf(query))
            path == "/favorite" && !isPost -> MockTemplates.renderListPage(fixture("favorite"), pageOf(query))
            path == "/favorite" && isPost -> favoriteToggle(postForm["aid"].orEmpty())
            path == "/watch_list" -> MockTemplates.renderListPage(fixture("favorite"), pageOf(query))
            path == "/login" && isPost -> MockTemplates.loginResponse()
            path == "/logout" -> MockTemplates.okResponse()
            path == "/daily" -> fixture("daily")
            path == "/daily_chk" && isPost -> MockTemplates.checkInResponse()
            else -> null
        }
    }

    private fun pageOf(query: Map<String, String>): Int = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    /** 推荐本本：page 1 用 fixture，page≥2 空 block（调用方按设计容空） */
    private fun promote(page: Int): String {
        val root = MockTemplates.json.parseToJsonElement(fixture("promote")).jsonObject
        val data = root["data"] as? JsonObject ?: return fixture("promote")
        val newData = if (page <= 1) data else JsonObject(
            data.mapValues { (k, v) -> if (v is JsonArray) JsonArray(emptyList()) else v },
        )
        return MockTemplates.json.encodeToString(JsonObject.serializer(), newData)
    }

    /** 收藏翻转：内存维护每个 aid 的状态，下一次调用翻转 */
    private val favState = HashMap<String, Boolean>()

    private fun favoriteToggle(aid: String): String {
        val added = !(favState[aid] ?: false)
        favState[aid] = added
        return MockTemplates.favoriteToggleResponse(aid, added)
    }

    /** 查询串解析（拦截器已按 & 与 = 拆好；此处做 URL 解码容错） */
    fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&')
            .mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }
            .toMap()
    }

    /** 仅用于单测的缓存清理 */
    fun resetForTest() {
        synchronized(cache) { cache.clear() }
        favState.clear()
    }

    /** 判断 fixture total 是否为字符串数字（favorite 是字符串 "26"，列表是裸数字）——单测口径 */
    internal fun totalOf(text: String): Long? =
        (MockTemplates.json.parseToJsonElement(text).jsonObject["data"] as? JsonObject)
            ?.get("total")?.let { (it as? JsonPrimitive)?.longOrNull }
}
