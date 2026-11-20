package com.jmread.mock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模拟数据模板的 JVM 单测：分页合成、详情/章节 id 对齐、乱序安全（photoId < 220980）、
 * 静态资源改写、登录/收藏翻转响应形态。
 */
class MockTemplatesTest {

    private fun data(text: String) = Json.parseToJsonElement(text).jsonObject["data"]!!.jsonObject

    private val listFixture = """
        {"code":200,"data":{"search_query":"[sample:search_query]","total":10000,"content":[
            {"id":"646603","author":"[sample:author]","name":"[sample:name]","image":"",
             "category":{"id":"5","title":"[sample:title]"},"category_sub":{"id":null,"title":null},
             "liked":false,"is_favorite":false,"update_at":1789918565,"adddate":"2024-10-08"},
            {"id":"1472726","author":"[sample:author]","name":"[sample:name]","image":"",
             "category":{"id":"1","title":"[sample:title]"},"category_sub":{"id":null,"title":null},
             "liked":false,"is_favorite":false,"update_at":1789800000,"adddate":"2024-10-07"}
        ]}}
    """.trimIndent()

    @Test
    fun `列表第一页保留 fixture 条目并归一化 total`() {
        val out = MockTemplates.renderListPage(listFixture, page = 1, maxPages = 3)
        val d = data(out)
        assertEquals(6L, d["total"]!!.jsonPrimitive.longOrNull) // 2 items × 3 pages
        assertEquals(2, d["content"]!!.jsonArray.size)
        assertEquals("646603", d["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `合成页 id 稳定且 update_at 逐页递减`() {
        val p2 = data(MockTemplates.renderListPage(listFixture, page = 2, maxPages = 3))
        val p2Again = data(MockTemplates.renderListPage(listFixture, page = 2, maxPages = 3))
        val p3 = data(MockTemplates.renderListPage(listFixture, page = 3, maxPages = 3))
        val id2 = p2["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        val id2Again = p2Again["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(id2, id2Again) // 同页重复请求结果一致（分页去重/缓存语义）
        val u2 = p2["content"]!!.jsonArray[0].jsonObject["update_at"]!!.jsonPrimitive.longOrNull!!
        val u3 = p3["content"]!!.jsonArray[0].jsonObject["update_at"]!!.jsonPrimitive.longOrNull!!
        assertTrue("第3页应比第2页更旧：u3=$u3 u2=$u2", u3 < u2)
        assertTrue("合成 id 不与 fixture 空间重叠：$id2", id2.toLong() >= 90_000_000)
    }

    @Test
    fun `超出页数上限返回空页`() {
        val out = MockTemplates.renderListPage(listFixture, page = 4, maxPages = 3)
        assertTrue(data(out)["content"]!!.jsonArray.isEmpty())
    }

    private val albumFixture = """
        {"code":200,"data":{"id":646603,"name":"[sample:name]","images":[],"description":"[sample:description]",
         "series":[{"id":"646603","name":"[sample:name]","sort":"1"},
                   {"id":"647249","name":"[sample:name]","sort":"2"}],
         "total_photos":5679,"likes":"134686"}}
    """.trimIndent()

    @Test
    fun `详情 id 对齐请求值且系列 photoId 落在不乱序区间`() {
        val out = MockTemplates.renderAlbum(albumFixture, albumId = "90001001")
        val d = data(out)
        assertEquals(90001001L, d["id"]!!.jsonPrimitive.longOrNull)
        assertEquals("模拟作品 #90001001", d["name"]!!.jsonPrimitive.content)
        val series = d["series"]!!.jsonArray
        assertEquals(2, series.size)
        series.forEachIndexed { i, el ->
            val photoId = el.jsonObject["id"]!!.jsonPrimitive.content.toLong()
            assertTrue("photoId=$photoId 必须 < 220980（不触发乱序还原）", photoId < 220_980)
            assertTrue(photoId >= 100_000)
            assertEquals("第${i + 1}话", el.jsonObject["name"]!!.jsonPrimitive.content)
        }
        // 不同作品系列 id 不相同
        val out2 = data(MockTemplates.renderAlbum(albumFixture, albumId = "90002002"))
        val s1 = d["series"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        val s2 = out2["series"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertTrue("不同作品的系列 id 应不同", s1 != s2)
    }

    @Test
    fun `章节 id 对齐请求 photoId`() {
        val fixture = """{"code":200,"data":{"id":646603,"images":["00001.webp","00002.webp"]}}"""
        val d = data(MockTemplates.renderChapter(fixture, photoId = "101001"))
        assertEquals("101001", d["id"]!!.jsonPrimitive.content)
        assertEquals(2, d["images"]!!.jsonArray.size)
    }

    @Test
    fun `论坛第2页为空列表且静态头像改写到模拟域名`() {
        val fixture = """
            {"code":200,"data":{"list":[{"cid":"1","content":"[sample:content]","image":"/static/avatar.png"}],"total":"31"}}
        """.trimIndent()
        val p1 = data(MockTemplates.renderForum(fixture, page = 1))
        assertEquals(
            "https://mock.jm.invalid/static/avatar.png",
            p1["list"]!!.jsonArray[0].jsonObject["image"]!!.jsonPrimitive.content,
        )
        val p2 = data(MockTemplates.renderForum(fixture, page = 2))
        assertTrue(p2["list"]!!.jsonArray.isEmpty())
        assertEquals("31", p2["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `登录与收藏翻转响应形态与 DTO 对齐`() {
        val login = Json.parseToJsonElement(MockTemplates.loginResponse()).jsonObject
        assertEquals("mock-avs-session-80001", login["data"]!!.jsonObject["s"]!!.jsonPrimitive.content)
        assertEquals("80001", login["data"]!!.jsonObject["uid"]!!.jsonPrimitive.content)

        val add = Json.parseToJsonElement(MockTemplates.favoriteToggleResponse("123", added = true)).jsonObject
        assertEquals("add", add["data"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val remove = Json.parseToJsonElement(MockTemplates.favoriteToggleResponse("123", added = false)).jsonObject
        assertEquals("remove", remove["data"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `列表条目列表键为 list 的端点（收藏流）同样分页`() {
        val fixture = """
            {"code":200,"data":{"list":[{"id":"1435723","name":"[sample:name]","image":"","update_at":1789918565}],"total":"26","count":2}}
        """.trimIndent()
        val d = data(MockTemplates.renderListPage(fixture, page = 1, maxPages = 3))
        assertEquals(3L, d["total"]!!.jsonPrimitive.longOrNull) // 1 item × 3 pages
        assertEquals(1, d["list"]!!.jsonArray.size)
        assertEquals(2, d["count"]!!.jsonPrimitive.content.toInt()) // 非列表字段原样保留
    }
}
