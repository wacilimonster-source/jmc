package com.jmread.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 榜单档位模型与「新晋热榜」选片逻辑。
 *
 * 背景：服务端日榜 `o=mv_t` 恒空（total=0，连测稳定；参考实现 jm_config.py 的
 * `ORDER_DAY_RANKING='mv_t'` 确认参数没写错，是服务端无数据）。
 * 于是 H24 档位改为由月榜数据本地按 `update_at` 重排得到，本文件固化这套规则。
 */
class RankTabTest {

    private lateinit var originalZone: java.util.TimeZone

    /** 日期断言与本地时区相关，钉死时区避免在别的机器上飘 */
    @Before
    fun pinTimezone() {
        originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimezone() {
        java.util.TimeZone.setDefault(originalZone)
    }

    private fun comic(id: String, lastUpdatedAt: Long, title: String = id) =
        ComicSummary(id = id, title = title, lastUpdatedAt = lastUpdatedAt)

    @Test
    fun `三个档位与线上参数的映射`() {
        assertEquals("mv_w", RankTab.D7.order)
        assertEquals("mv_m", RankTab.D30.order)
        // H24 不是服务端档位，没有对应的 o= 取值（由月榜数据本地重排）
        assertEquals(null, RankTab.H24.order)
        // 有服务端参数的档位必须恰好是周/月两个
        assertEquals(listOf(RankTab.MV, RankTab.D30, RankTab.D7), RankTab.entries.filter { it.order != null })
    }

    @Test
    fun `of 能按 value 还原且未知值回退月榜`() {
        assertEquals(RankTab.H24, RankTab.of("H24"))
        assertEquals(RankTab.D7, RankTab.of("D7"))
        assertEquals(RankTab.D30, RankTab.of("D30"))
        assertEquals(RankTab.D30, RankTab.of("mv_t"))
        assertEquals(RankTab.D30, RankTab.of(null))
    }

    @Test
    fun `乐观档位表非空且含默认档位`() {
        assertTrue(RankTab.optimistic.isNotEmpty())
        assertTrue(RankTab.optimistic.contains(RankTab.H24))
    }

    @Test
    fun `新晋热榜按最近更新倒序取前 N`() {
        val pool = listOf(
            comic("a", 100),
            comic("b", 500),
            comic("c", 300),
            comic("d", 900),
        )
        val picked = pickNewArrivals(pool, limit = 3, min = 1)
        assertEquals(listOf("d", "b", "c"), picked.map { it.id })
    }

    @Test
    fun `时间戳全为 0 时回退池子原序而不是返回空`() {
        val pool = (1..30).map { comic("id$it", 0) }
        val picked = pickNewArrivals(pool, limit = 40, min = 12)
        // 排序后仍有 30 条（>= min），所以按排序结果返回，但顺序是稳定原序
        assertEquals(30, picked.size)
        assertEquals("id1", picked.first().id)
    }

    @Test
    fun `池子太小不足下限时回退原序`() {
        val pool = listOf(comic("a", 900), comic("b", 800))
        val picked = pickNewArrivals(pool, limit = 40, min = 12)
        // 只有 2 条 < min=12：回退成池子原序（避免出现只有 2 条的稀疏列表）
        assertEquals(listOf("a", "b"), picked.map { it.id })
    }

    @Test
    fun `空池返回空`() {
        assertTrue(pickNewArrivals(emptyList(), limit = 40, min = 12).isEmpty())
    }

    @Test
    fun `limit 生效且不超出池子大小`() {
        val pool = (1..100).map { comic("id$it", it.toLong()) }
        val picked = pickNewArrivals(pool, limit = 40, min = 12)
        assertEquals(40, picked.size)
        // 时间戳最大的 40 个（id100..id61）应排在最前
        assertEquals("id100", picked.first().id)
        assertEquals("id61", picked.last().id)
    }

    /**
     * 展示用「更新时间」的取值优先级。
     *
     * 必须优先 update_at：列表的服务端顺序（浏览流 o=mr、榜单）是按 update_at 排的，
     * 实测 o=mr 前 12 条 update_at 严格降序、adddate 完全乱序。
     * 用反了会让「最新」列表显示一堆 2019/2020 年的日期。
     */
    @Test
    fun `展示日期优先最近更新而不是原始发布日`() {
        // update_at = 2026-09-20，adddate 是 2019 年的原始发布日 -> 取前者
        assertEquals("2026-09-20", displayUpdatedAt(1789918565L, "2019-12-25"))
    }

    @Test
    fun `update_at 缺失时才退回 adddate`() {
        assertEquals("2019-12-25", displayUpdatedAt(0L, "2019-12-25"))
        assertEquals("2019-12-25", displayUpdatedAt(-1L, "2019-12-25"))
    }

    @Test
    fun `两者都缺时返回空串`() {
        assertEquals("", displayUpdatedAt(0L, ""))
    }

    @Test
    fun `formatUnixDate 处理边界值`() {
        assertEquals("", formatUnixDate(0))
        assertEquals("", formatUnixDate(-100))
        assertEquals("2026-09-20", formatUnixDate(1789918565L))
    }
}
