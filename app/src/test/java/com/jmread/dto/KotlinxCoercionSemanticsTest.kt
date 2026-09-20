package com.jmread.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class OnlyString(val v: String = "")

@Serializable
private data class OnlyInt(val v: Int = 0)

@Serializable
private data class OnlyLong(val v: Long = 0L)

@Serializable
private data class OnlyBoolean(val v: Boolean = false)

/**
 * kotlinx-serialization 强制转换语义固化测试（勿凭印象改 DTO，先看这里）。
 *
 * 为什么要专门测：禁漫线上**同一字段会在不同条目里给不同类型**
 * （2026-09-21 实测：/categories 首条 `id` 是数字 0、其余是字符串 "1"；
 *   /forum `total` 是字符串 "1075"；/chapter `id` 是数字 646603）。
 * DTO 类型写错时 kotlinx 是「抛」还是「自动转」，决定功能是静默变空还是崩溃。
 *
 * 实测结论（本测试固化）——**方向不对称**：
 *   数字  -> String   ✗ 抛异常   ← 唯一会炸的方向，必须靠 String 声明正确来防
 *   字符串-> Int/Long ✓ 自动转（"1075" 能解进 Int）
 *   字符串-> Boolean  ✓ 自动转
 *   null  -> 非空字段  ✓ 由 coerceInputValues 兜成默认值
 *
 * 推论：DTO 里所有「线上可能是数字」的字段（id / total 之类）若声明成 String，
 * 就是定时炸弹；反之声明成数字则两种形态都能吃下。
 */
class KotlinxCoercionSemanticsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `JSON 数字解进 String 会抛异常`() {
        val r = runCatching { json.decodeFromString(OnlyString.serializer(), """{"v":0}""") }
        assertTrue(
            "数字不能被解进 String —— 这是唯一会整包解析失败的方向",
            r.isFailure,
        )
    }

    @Test
    fun `JSON 字符串数字解进 Int 会被自动转换`() {
        val v = json.decodeFromString(OnlyInt.serializer(), """{"v":"1075"}""")
        assertEquals(1075, v.v)
    }

    @Test
    fun `JSON 字符串数字解进 Long 会被自动转换`() {
        val v = json.decodeFromString(OnlyLong.serializer(), """{"v":"74821127"}""")
        assertEquals(74821127L, v.v)
    }

    @Test
    fun `JSON 字符串布尔解进 Boolean 会被自动转换`() {
        val v = json.decodeFromString(OnlyBoolean.serializer(), """{"v":"true"}""")
        assertTrue(v.v)
    }

    @Test
    fun `JSON 裸数字解进 Int 正常`() {
        assertEquals(0, json.decodeFromString(OnlyInt.serializer(), """{"v":0}""").v)
    }

    @Test
    fun `JSON null 解进非空 String 由 coerceInputValues 兜成默认值`() {
        assertEquals("", json.decodeFromString(OnlyString.serializer(), """{"v":null}""").v)
    }

    @Test
    fun `JSON null 解进非空 Int 由 coerceInputValues 兜成默认值`() {
        assertEquals(0, json.decodeFromString(OnlyInt.serializer(), """{"v":null}""").v)
    }
}
