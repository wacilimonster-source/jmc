package com.jmread.dto

import com.jmread.network.JmAlbumResponse
import com.jmread.network.JmCategoriesResponse
import com.jmread.network.JmChapterResponse
import com.jmread.network.JmForumResponse
import com.jmread.network.JmListResponse
import com.jmread.network.JmSettingResponse
import com.jmread.network.JmWeekResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反序列化回归（防 PiKA D1 复发）：7 份真实响应 fixture 必须可解且关键字段形态正确。
 *
 * PiKA 的教训：DTO 字段类型「照社区库猜」（category 声明 String 而线上是对象、
 * author 声明 String 而线上是数组），被 isLenient/ignoreUnknownKeys 掩盖后潜伏 16 天。
 * 本测试以 2026-09-19 实测形态固化 fixture——服务端字段一变先红在这里。
 */
class JmDtoFixtureTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("jm/$name.json")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `list - category 是对象而非字符串`() {
        val resp = json.decodeFromString(JmListResponse.serializer(), fixture("list"))
        val item = resp.data.content.first()
        assertEquals("同人志", item.category.titleText)
        assertEquals("漢化", item.categorySub.titleText)
        assertEquals("441295", item.id)
        assertEquals(10000, resp.data.total) // 浏览流哨兵值
        // 第二条：is_favorite 与 liked 可解
        assertTrue(resp.data.content[1].isFavorite)
        assertTrue(resp.data.content[1].liked)
    }

    /**
     * 2026-09-21 线上实测：浏览流 23 条、搜索 20 条的 category_sub 都是 `{id:null,title:null}`。
     *
     * 这条必须用内联 JSON 而不是 fixture —— 手写 fixture 里没有 null，
     * 盖不住这个形态。此前 JmNamedItem 按非空 String 声明，能跑通完全依赖
     * `coerceInputValues = true` 把 null 强转成默认值，等于让掉了 ADR-6 的防线。
     */
    @Test
    fun `list - category_sub 为 null 时可解且回退空串`() {
        val raw = """
            {"code":200,"errorMsg":"","data":{"total":10000,"content":[
              {"id":"646603","name":"x","author":"a","image":"",
               "category":{"id":"5","title":"韩漫"},
               "category_sub":{"id":null,"title":null},
               "liked":false,"is_favorite":false,"update_at":1789918565,"adddate":"2024-10-08"}
            ]}}
        """.trimIndent()
        val resp = json.decodeFromString(JmListResponse.serializer(), raw)
        val item = resp.data.content.first()
        assertEquals("韩漫", item.category.titleText)
        assertEquals("", item.categorySub.titleText)
        assertEquals("", item.categorySub.idText)
    }

    @Test
    fun `search - description 可为 null 且 total 真实`() {
        val resp = json.decodeFromString(JmListResponse.serializer(), fixture("search"))
        assertEquals(721, resp.data.total)
        assertEquals("这是搜索结果额外返回的简介", resp.data.content.first().description)
    }

    @Test
    fun `album - author 是数组 series 带 sort 统计是字符串`() {
        val resp = json.decodeFromString(JmAlbumResponse.serializer(), fixture("album"))
        val d = resp.data
        assertEquals(listOf("美娜讚", "鋼鐵王", "NTR"), d.author)   // 数组，不是 String
        assertEquals(441295L, d.id)                                // 裸数字，不是 String
        assertEquals("74821127", d.totalViews)                     // 字符串统计
        assertEquals("12345", d.likes)
        assertEquals("67", d.commentTotal)
        assertTrue(d.isFavorite)                                   // 收藏态读侧回填依据
        // series 三项：sort 字段存在，空名可回退
        assertEquals(3, d.series.size)
        assertEquals("3", d.series[1].sort)
        assertEquals("", d.series[1].name)
        assertEquals(2, d.relatedList.size)
        assertEquals("", d.price)                                  // 非付费本
    }

    @Test
    fun `chapter - images 文件名列表可解`() {
        val resp = json.decodeFromString(JmChapterResponse.serializer(), fixture("chapter"))
        assertEquals(listOf("00001.webp", "00002.webp", "00003.webp", "00004.webp"), resp.data.images)
        assertEquals("552001", resp.data.id)
    }

    @Test
    fun `forum - 主键是 CID expinfo 是对象 replys 内嵌`() {
        val resp = json.decodeFromString(JmForumResponse.serializer(), fixture("forum"))
        val first = resp.data.list.first()
        assertEquals("881001", first.cid)          // 真实主键 CID，不是 id
        assertEquals("Lv.12", first.expinfo.levelName)
        assertEquals(12, first.expinfo.level)
        assertEquals(listOf("badge1"), first.expinfo.badges)
        assertEquals(1, first.replys.size)
        assertEquals("881002", first.replys.first().cid)
        assertEquals("881001", first.replys.first().parentCid)
        // 剧透标记
        assertEquals("1", resp.data.list[2].spoiler)
    }

    @Test
    fun `categories - blocks 官方标签组可解`() {
        val resp = json.decodeFromString(JmCategoriesResponse.serializer(), fixture("categories"))
        assertEquals(2, resp.data.categories.size)
        assertEquals(349441, resp.data.categories.first().totalAlbums)
        assertEquals("doujin-chinese", resp.data.categories.first().subCategories.first().slug)
        // blocks = 标签墙数据源
        assertEquals(2, resp.data.blocks.size)
        assertEquals(listOf("調教", "禁漫漢化組", "胃疼"), resp.data.blocks.first().content)
    }

    @Test
    fun `week - 期数列表可解`() {
        val resp = json.decodeFromString(JmWeekResponse.serializer(), fixture("week"))
        assertEquals(3, resp.data.categories.size)
        assertEquals("第257期", resp.data.categories.first().time)
    }

    @Test
    fun `setting - data 是明文对象不走解密分支`() {
        val resp = json.decodeFromString(JmSettingResponse.serializer(), fixture("setting"))
        assertEquals("https://www.cdngwc.cc", resp.data.baseUrl)
        assertEquals("cdn-msp12.jmdanjonproxy.xyz", resp.data.imgHost)
        assertEquals("2.1.8", resp.data.jm3Version)
        assertNotNull(resp.data.mainWebHost)
        assertFalse(resp.data.appShunts.isEmpty())
    }
}
