package com.jmread.mock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 模拟数据模板渲染（纯 Kotlin，可 JVM 单测）。
 *
 * fixture 与单测同源（app/src/test/resources/jm，dump-fixtures.mjs 抓取并脱敏），
 * 模拟器复测由此获得「真实形态 + 模拟内容」，不再依赖真实账号/真实网络。
 *
 * 关键约束：
 *  - 系列章节 id 必须落在 < 220980（JmScramble 规则：小 id 不乱序还原），
 *    否则本地生成的占位图会被当乱序图还原，渲染成错乱拼图。
 *  - 列表分页把 total 归一化为 items×maxPages，分页条与「到底了」语义一致。
 */
object MockTemplates {

    val json = Json { ignoreUnknownKeys = true }

    /** 模拟图片域名：.invalid 顶级域保证钩子失效时立即 DNS 失败，而不是打到真实 CDN */
    const val MOCK_HOST = "https://mock.jm.invalid"

    /** 列表流模拟页数上限 */
    const val LIST_MAX_PAGES = 3

    /** 系列章节 photoId：< 220980（不触发乱序还原），且由 albumId 决定、album 内按序号区分 */
    internal fun photoIdFor(albumId: Long, index: Int): String {
        val base = 100000L + ((albumId * 7L + (index + 1) * 13L) % 120000L)
        return base.toString()
    }

    /** 模拟列表作品的稳定 id：分页 2+ 页合成用，不与 fixture id 空间重叠 */
    internal fun syntheticId(page: Int, index: Int): Long = 90_000_000L + page * 1000L + index

    private fun dataOf(root: JsonObject): JsonObject =
        root["data"] as? JsonObject ?: JsonObject(emptyMap())

    private fun itemsOf(data: JsonObject): Pair<String, JsonArray> = when {
        (data["content"] as? JsonArray) != null -> "content" to data["content"] as JsonArray
        (data["list"] as? JsonArray) != null -> "list" to data["list"] as JsonArray
        else -> "content" to JsonArray(emptyList())
    }

    /** 列表/搜索/收藏流：按页渲染。page 1 用 fixture 原条目，2..maxPages 合成条目，超出为空页 */
    fun renderListPage(fixtureText: String, page: Int, maxPages: Int = LIST_MAX_PAGES): String {
        val root = json.parseToJsonElement(fixtureText).jsonObject
        val data = dataOf(root)
        val (key, base) = itemsOf(data)
        val items: JsonArray = when {
            page <= 1 -> base
            page <= maxPages -> JsonArray(base.mapIndexedNotNull { i, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val id = syntheticId(page, i)
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "[sample:name]"
                buildJsonObject {
                    obj.forEach { (k, v) -> put(k, v) }
                    put("id", JsonPrimitive(id.toString()))
                    put("name", JsonPrimitive("[模拟] 第${page}页 · ${name.take(12)}"))
                    // update_at 逐页递减：排行榜「最新在前」的稳定序，跨页不跳变
                    val u = (obj["update_at"] as? JsonPrimitive)?.longOrNull ?: 1_700_000_000L
                    put("update_at", JsonPrimitive(u - page * 100_000L - i * 1_000L))
                }
            })
            else -> JsonArray(emptyList())
        }
        // total 归一化：分页条页数 = maxPages；空页保留 total 以免详情分页横幅跳变
        val total = (data["total"] as? JsonPrimitive)?.longOrNull ?: items.size.toLong()
        val patchedTotal = if (items.isEmpty()) total else base.size.toLong() * maxPages
        val newData = buildJsonObject {
            data.forEach { (k, v) -> put(k, v) }
            put(key, items)
            put("total", JsonPrimitive(patchedTotal))
        }
        return encode(root, newData)
    }

    /** 详情：id/名称对齐请求的作品，系列章节 id 改写为「不乱序」的模拟 photoId */
    fun renderAlbum(fixtureText: String, albumId: String): String {
        val root = json.parseToJsonElement(fixtureText).jsonObject
        val data = dataOf(root)
        val aid = albumId.toLongOrNull() ?: 646603L
        val newData = buildJsonObject {
            data.forEach { (k, v) -> put(k, v) }
            put("id", JsonPrimitive(aid))
            put("name", JsonPrimitive("模拟作品 #$albumId"))
            val series = (data["series"] as? JsonArray) ?: JsonArray(emptyList())
            put("series", JsonArray(series.mapIndexed { i, el ->
                val obj = el as? JsonObject ?: el
                if (obj is JsonObject) buildJsonObject {
                    obj.forEach { (k, v) -> put(k, v) }
                    put("id", JsonPrimitive(photoIdFor(aid, i)))
                    put("name", JsonPrimitive("第${i + 1}话"))
                } else obj
            }))
        }
        return encode(root, newData)
    }

    /** 章节：id 对齐请求的 photoId；图片保持文件名列表（URL 由 JmCrypto 按禁漫规则拼装） */
    fun renderChapter(fixtureText: String, photoId: String): String {
        val root = json.parseToJsonElement(fixtureText).jsonObject
        val data = dataOf(root)
        val newData = buildJsonObject {
            data.forEach { (k, v) -> put(k, v) }
            put("id", JsonPrimitive(photoId))
        }
        return encode(root, newData)
    }

    /** 评论/周筛选等：/static/ 开头的头像与图片路径改写到模拟域名（逐条递增编号） */
    fun rewriteStaticAssets(root: JsonElement): JsonElement = when (root) {
        is JsonObject -> JsonObject(root.mapValues { (_, v) -> rewriteStaticAssets(v) })
        is JsonArray -> JsonArray(root.map { rewriteStaticAssets(it) })
        is JsonPrimitive -> if (root.isString) {
            val s = root.content
            if (s.startsWith("/static/")) JsonPrimitive("$MOCK_HOST$s") else root
        } else root
        is JsonNull -> root
        else -> root
    }

    /** 论坛分页：page 1 渲染 fixture（头像改写），page≥2 空列表（total 保持，供「下一页」语义） */
    fun renderForum(fixtureText: String, page: Int): String {
        val root = json.parseToJsonElement(fixtureText).jsonObject
        val data = dataOf(root)
        val patched = if (page <= 1) data else buildJsonObject {
            data.forEach { (k, v) -> put(k, v) }
            put("list", JsonArray(emptyList()))
        }
        return encode(root, rewriteStaticAssets(patched) as JsonObject)
    }

    /** 登录响应：与 JmLoginData 对齐（s=会话, uid=用户 id） */
    fun loginResponse(uid: String = "80001"): String =
        """{"code":200,"data":{"s":"mock-avs-session-$uid","uid":"$uid"}}"""

    /** 收藏翻转响应：type 是机器可读的翻转结果（add / remove），由 MockState 维护翻转状态 */
    fun favoriteToggleResponse(aid: String, added: Boolean): String =
        """{"code":200,"data":{"status":"ok","msg":"${if (added) "漫画添加到您最喜爱的清单!" else "已移除收藏"}","type":"${if (added) "add" else "remove"}","aid":"$aid"}}"""

    /** 签到响应 */
    fun checkInResponse(): String =
        """{"code":200,"data":{"status":"ok","msg":"Jcoin:40 EXP:100"}}"""

    /** 通用空动作响应 */
    fun okResponse(): String = """{"code":200}"""

    private fun encode(root: JsonObject, data: JsonObject): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            root.forEach { (k, v) -> if (k != "data") put(k, v) }
            put("data", data)
        },
    )
}
