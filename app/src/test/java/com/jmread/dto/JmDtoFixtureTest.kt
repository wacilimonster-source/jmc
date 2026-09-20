package com.jmread.dto

import com.jmread.network.JmAlbumResponse
import com.jmread.network.JmCategoriesResponse
import com.jmread.network.JmChapterResponse
import com.jmread.network.JmForumResponse
import com.jmread.network.JmListResponse
import com.jmread.network.JmSettingResponse
import com.jmread.network.JmWeekResponse
import com.jmread.network.parseSettingLeniently
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反序列化回归（防 PiKA D1 复发）：9 份 fixture 必须可解且关键字段形态正确。
 *
 * PiKA 的教训：DTO 字段类型「照社区库猜」（category 声明 String 而线上是对象、
 * author 声明 String 而线上是数组），被 isLenient/ignoreUnknownKeys 掩盖后潜伏 16 天。
 *
 * fixture 由 `node tools/dump-fixtures.mjs` 从真实响应生成（自由文本已脱敏为
 * `[sample:<字段名>]`，类型/结构/数组长度原样保留）。手写 fixture 已被证明会掩盖 bug：
 * 2026-09-21 累计因手写 fixture 漏掉 3 个致命类型错误（app_shunts / expinfo.badges /
 * categories[].id），全部是「声明 String 或 List<String>，线上却是数字或对象」。
 *
 * 断言只锁结构与稳定值（id / 计数 / 形态），不锁会被服务端轮换的值。
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
        assertEquals("[sample:title]", item.category.titleText)
        assertEquals("5", item.category.idText)
        assertEquals("646603", item.id)
        assertEquals(10000, resp.data.total)          // 浏览流哨兵值
        assertEquals(80, resp.data.content.size)      // 页大小
        // 第二条：is_favorite 与 liked 可解
        assertEquals(false, resp.data.content[1].isFavorite)
        assertEquals(false, resp.data.content[1].liked)
    }

    /**
     * 2026-09-21 线上实测：浏览流 / 搜索的 category_sub 都是 `{id:null,title:null}`。
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

    /** fixture 里 category_sub 就是 {id:null,title:null}，与上面的内联用例互为印证 */
    @Test
    fun `list - fixture 里的 category_sub 同为 null 形态`() {
        val resp = json.decodeFromString(JmListResponse.serializer(), fixture("list"))
        assertEquals("", resp.data.content.first().categorySub.titleText)
        assertEquals("", resp.data.content.first().categorySub.idText)
    }

    @Test
    fun `search - total 是真实命中数且 description 可为 null`() {
        val resp = json.decodeFromString(JmListResponse.serializer(), fixture("search"))
        assertEquals(722, resp.data.total)             // 搜索是真实命中数，不是 10000 哨兵
        assertEquals(80, resp.data.content.size)
        assertNull(resp.data.content.first().description)
    }

    /**
     * /week/filter 的列表键是 `list` 而不是 `content`。
     * 这是「两个键都带默认值 -> 键名写错不抛异常只给空列表」的典型场景，
     * 每周必看某期曾因此永远显示空。fixture 把该端点的真实键固化成回归。
     */
    @Test
    fun `weekFilter - 列表键是 list 而非 content 且 items 取得到`() {
        val resp = json.decodeFromString(JmListResponse.serializer(), fixture("weekFilter"))
        assertTrue("content 键在该端点不存在，必须是空的", resp.data.content.isEmpty())
        assertEquals(11, resp.data.list.size)
        assertEquals(11, resp.data.total)
        // items 是统一取值口：content 空则回退 list
        assertEquals(11, resp.data.items.size)
        assertEquals("1470691", resp.data.items.first().id)
    }

    @Test
    fun `album - author 是数组 series 带 sort 统计是字符串`() {
        val resp = json.decodeFromString(JmAlbumResponse.serializer(), fixture("album"))
        val d = resp.data
        assertEquals(listOf("[sample:author]", "[sample:author]"), d.author)  // 数组，不是 String
        assertEquals(646603L, d.id)                                          // 裸数字，不是 String
        assertEquals("783735", d.totalViews)                                 // 字符串统计
        assertEquals("134686", d.likes)
        assertEquals("1075", d.commentTotal)
        assertEquals(false, d.isFavorite)                                    // 收藏态读侧回填依据
        // series：sort 字段存在，空名可回退；85 话
        assertEquals(85, d.series.size)
        assertEquals("2", d.series[1].sort)
        assertEquals("[sample:name]", d.series[1].name)
        assertEquals(12, d.relatedList.size)
        assertEquals("", d.price)                                            // 非付费本
        // 详情接口不返回图片（images 恒空，图在 /chapter）
        assertTrue(d.images.isEmpty())
    }

    @Test
    fun `chapter - id 线上是裸数字 images 文件名列表可解`() {
        val resp = json.decodeFromString(JmChapterResponse.serializer(), fixture("chapter"))
        // 线上 data.id 是裸数字 646603；声明 String 会整包解析失败（见 JmFlexibleStringSerializer）
        assertEquals("646603", resp.data.id)
        assertEquals(86, resp.data.images.size)
        assertEquals(
            listOf("00001.webp", "00002.webp", "00003.webp", "00004.webp"),
            resp.data.images.take(4),
        )
    }

    @Test
    fun `forum - 主键是 CID total 是字符串数字 replys 可缺省`() {
        val resp = json.decodeFromString(JmForumResponse.serializer(), fixture("forum"))
        val d = resp.data
        assertEquals(10, d.list.size)
        assertEquals(1075, d.total)                 // 线上是字符串 "1075"
        val first = d.list.first()
        assertEquals("11070554", first.cid)         // 真实主键 CID，不是 id
        assertEquals("0", first.parentCid)
        assertEquals("1", first.spoiler)
        assertEquals("2", d.list[5].spoiler)        // 剧透值不止 "1"
        assertEquals("[sample:username]", first.username)
        // replys 键在多数评论上直接缺失 -> 靠默认值兜成空表，不能抛
        assertTrue(first.replys.isEmpty())
    }

    /**
     * 2026-09-21 实测：expinfo.badges 是**对象数组** `[{content,name,id}]`，
     * 不是字符串数组。声明 List<String> 会让整个 /forum 解析失败（评论页全空）。
     * 手写 fixture 里写的是 `["badge1"]`，正好把这个错误掩盖了。
     */
    @Test
    fun `forum - expinfo 等级与徽章对象数组`() {
        val resp = json.decodeFromString(JmForumResponse.serializer(), fixture("forum"))
        val withBadges = resp.data.list[7]
        assertEquals("[sample:level_name]", withBadges.expinfo.levelName)
        assertEquals(12, withBadges.expinfo.level)
        assertEquals(5, withBadges.expinfo.badges.size)
        assertEquals("171", withBadges.expinfo.badges[0].id)
        assertEquals("[sample:name]", withBadges.expinfo.badges[0].name)
        assertEquals("[sample:content]", withBadges.expinfo.badges[0].content)
        // 没有徽章的评论是空数组
        assertTrue(resp.data.list[0].expinfo.badges.isEmpty())
    }

    @Test
    fun `forum - 子评论内嵌 replys 且 parent_CID 指向父评论`() {
        val resp = json.decodeFromString(JmForumResponse.serializer(), fixture("forum"))
        val parent = resp.data.list[9]
        assertEquals("10999711", parent.cid)
        assertEquals(1, parent.replys.size)
        assertEquals("11004361", parent.replys.first().cid)
        assertEquals("10999711", parent.replys.first().parentCid)
    }

    @Test
    fun `categories - 首条 id 是数字 0 其余是字符串 total_albums 混合形态`() {
        val resp = json.decodeFromString(JmCategoriesResponse.serializer(), fixture("categories"))
        val d = resp.data
        assertEquals(10, d.categories.size)
        // 首条「最新A漫」线上 id 是裸数字 0，其余是字符串 "1".."9"
        assertEquals("0", d.categories[0].id)
        assertEquals("1", d.categories[1].id)
        // total_albums 线上数字与字符串混用；Int 两种都能吃
        assertEquals(0, d.categories[0].totalAlbums)
        assertEquals(349604, d.categories[1].totalAlbums)
        // 首条没有 sub_categories 键 -> 默认空表
        assertTrue(d.categories[0].subCategories.isEmpty())
        assertEquals("doujin", d.categories[1].slug)
        assertEquals("chinese", d.categories[1].subCategories.first().slug)
        // blocks = 标签墙数据源（4 组）
        assertEquals(4, d.blocks.size)
        assertTrue(d.blocks.first().content.isNotEmpty())
        assertEquals("[sample:content]", d.blocks.first().content.first())
    }

    @Test
    fun `week - 期数列表可解`() {
        val resp = json.decodeFromString(JmWeekResponse.serializer(), fixture("week"))
        assertEquals(257, resp.data.categories.size)
        assertEquals("258", resp.data.categories.first().id)
        assertEquals("2026第257期09.18 - 09.11", resp.data.categories.first().time)
    }

    @Test
    fun `setting - data 是明文对象不走解密分支`() {
        val resp = json.decodeFromString(JmSettingResponse.serializer(), fixture("setting"))
        val d = resp.data
        assertTrue(d.baseUrl.startsWith("https://"))
        assertEquals(d.baseUrl, d.cnBaseUrl)
        assertTrue(d.mainWebHost.isNotBlank())
        // img_host 线上带 scheme（DomainPool 消费时才剥离）
        assertTrue(d.imgHost.startsWith("https://"))
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(d.jm3Version))
        // app_shunts 是对象数组（title 为展示文案，dump-fixtures 已脱敏）
        assertEquals(4, d.appShunts.size)
        assertEquals("[sample:title]", d.appShunts[0].title)
        assertEquals(1, d.appShunts[0].key)
    }

    /**
     * 2026-09-21 真机事故：/setting.app_shunts 线上是**对象数组** `[{title,key}]`，
     * 而 DTO 曾声明 `List<String>`，导致 /setting 整体解析抛
     * `Expected beginning of the string, but got '{'`。
     *
     * /setting 是「域名四级自愈」的唯一数据源，解析失败 = 自愈链路静默失效。
     * 桌面单测没抓到，是因为 fixture 里这条是手写的字符串数组——形状本身就是错的。
     * 本用例用内联 JSON 固化线上真实形状，防止 DTO 再被改回 List<String>。
     */
    @Test
    fun `setting - app_shunts 是对象数组而非字符串数组`() {
        val raw = """
            {"code":200,"errorMsg":"","data":{
              "base_url":"https://www.cdngwc.cc",
              "img_host":"https://cdn-msp3.jmapiproxy1.cc",
              "jm3_version":"2.1.8",
              "app_shunts":[{"title":"图源1","key":1},{"title":"图源2","key":2}]
            }}
        """.trimIndent()
        val resp = json.decodeFromString(JmSettingResponse.serializer(), raw)
        assertEquals(2, resp.data.appShunts.size)
        assertEquals("图源1", resp.data.appShunts[0].title)
        assertEquals(1, resp.data.appShunts[0].key)
        // 关键字段不受影响：即便 shunts 形态再变，五个自愈字段仍要拿到
        assertEquals("https://www.cdngwc.cc", resp.data.baseUrl)
        assertEquals("2.1.8", resp.data.jm3Version)
    }

    /**
     * 兜底路径：类型再次漂移时，严格 DTO 必须失败（保持告警能力），
     * 而宽松解析必须仍能取到五个自愈字段（保住真机上的域名自愈链路）。
     */
    @Test
    fun `setting - 字段类型漂移时宽松兜底仍能取到自愈字段`() {
        // app_shunts 改成数字数组（模拟服务端再次改形态）
        val drifted = """
            {"code":200,"errorMsg":"","data":{
              "base_url":"https://www.cdngwc.cc",
              "cn_base_url":"https://www.cdngwc.cc",
              "main_web_host":"18comic.vip",
              "img_host":"https://cdn-msp3.jmapiproxy1.cc",
              "jm3_version":"2.1.9",
              "app_shunts":[1,2,3]
            }}
        """.trimIndent()
        val strictFailed = runCatching {
            json.decodeFromString(JmSettingResponse.serializer(), drifted)
        }.isFailure
        assertTrue("类型漂移必须让严格 DTO 解析失败，否则测试失去告警能力", strictFailed)

        val d = parseSettingLeniently(drifted)!!
        assertEquals("https://www.cdngwc.cc", d.baseUrl)
        assertEquals("18comic.vip", d.mainWebHost)
        assertEquals("https://cdn-msp3.jmapiproxy1.cc", d.imgHost)
        assertEquals("2.1.9", d.jm3Version)
    }

    @Test
    fun `setting - data 缺失或非 JSON 时宽松兜底返回 null`() {
        assertNull(parseSettingLeniently("""{"code":200,"errorMsg":""}"""))
        assertNull(parseSettingLeniently("not json at all"))
        // data 存在但五个自愈字段全缺 → 视为无有效内容
        assertNull(parseSettingLeniently("""{"code":200,"data":{"is_cn":1}}"""))
    }
}
