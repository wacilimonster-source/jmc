package com.jmread.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者署名解析单测（对标 PiKA v1.10.0 的 AuthorNameTest 18 条）。
 * 覆盖：拆分/角色/括号/去重/上限/单作者直达/完整署名出口。
 */
class AuthorNameTest {

    @Test
    fun `单作者保持单一片段且不弹层`() {
        val p = AuthorName.parse("张三")
        assertFalse(p.hasMultiple)
        assertEquals(listOf("张三"), p.segments.map { it.text })
        assertEquals(AuthorRole.UNKNOWN, p.segments[0].role)
    }

    @Test
    fun `联合署名按斜杠拆分`() {
        val p = AuthorName.parse("张三 / 李四")
        assertTrue(p.hasMultiple)
        assertEquals(listOf("张三", "李四"), p.segments.map { it.text })
    }

    @Test
    fun `角色括号拆出翻译`() {
        val p = AuthorName.parse("张三（翻译：王五）")
        assertTrue(p.hasMultiple)
        assertEquals("张三", p.segments[0].text)
        assertEquals(AuthorRole.UNKNOWN, p.segments[0].role)
        assertEquals("王五", p.segments[1].text)
        assertEquals(AuthorRole.TRANSLATION, p.segments[1].role)
    }

    @Test
    fun `无角色括号内容为其他署名`() {
        val p = AuthorName.parse("BlossomSphere (桜湯ハル)")
        assertTrue(p.hasMultiple)
        assertEquals("BlossomSphere", p.segments[0].text)
        assertEquals(AuthorRole.OTHER, p.segments[1].role)
        assertEquals("桜湯ハル", p.segments[1].text)
    }

    @Test
    fun `整段带角色前缀的名字被正确识别`() {
        val p = AuthorName.parse("翻译：王五")
        assertFalse(p.hasMultiple)
        assertEquals("王五", p.segments[0].text)
        assertEquals(AuthorRole.TRANSLATION, p.segments[0].role)
    }

    @Test
    fun `全角分隔符与顿号也能拆分`() {
        val p = AuthorName.parse("张三＆李四、王五")
        assertTrue(p.hasMultiple)
        assertEquals(listOf("张三", "李四", "王五"), p.segments.map { it.text })
    }

    @Test
    fun `汉化组前缀识别为汉化角色`() {
        val p = AuthorName.parse("[汉化组]（翻译：某人）漫画家")
        assertTrue(p.segments.any { it.role == AuthorRole.GROUP || it.role == AuthorRole.TRANSLATION })
        assertTrue(p.segments.any { it.text == "漫画家" || it.text.contains("漫画家") })
    }

    @Test
    fun `重复片段去重且大小写不敏感`() {
        val p = AuthorName.parse("sage / Sage / SAGE")
        assertFalse("去重后应只剩一个片段", p.hasMultiple)
        assertEquals(1, p.segments.size)
    }

    @Test
    fun `片段数封顶不超过 6`() {
        val p = AuthorName.parse("a / b / c / d / e / f / g / h")
        assertEquals(6, p.segments.size)
    }

    @Test
    fun `空署名返回空片段`() {
        assertTrue(AuthorName.parse("").segments.isEmpty())
        assertTrue(AuthorName.parse("  ").segments.isEmpty())
    }

    @Test
    fun `空括号与纯装饰括号不产生空片段`() {
        val p = AuthorName.parse("张三（）")
        assertFalse(p.hasMultiple)
        assertEquals(listOf("张三"), p.segments.map { it.text })
    }

    @Test
    fun `完整署名永远是兜底出口`() {
        val origin = "张三 / 李四（翻译：王五）"
        val full = AuthorName.fullSegment(origin)
        assertEquals(origin, full.text)
    }

    @Test
    fun `角色前缀全角冒号与等号均支持`() {
        assertEquals(AuthorRole.TRANSLATION, AuthorName.parse("翻譯=李四").segments[0].role)
        assertEquals("李四", AuthorName.parse("翻譯=李四").segments[0].text)
    }

    @Test
    fun `日期样数字段不会被角色前缀误切`() {
        // 「2024:夏」前缀不是已知角色 → 不拆，保持原样
        val p = AuthorName.parse("2024:夏")
        assertFalse(p.hasMultiple)
        assertEquals("2024:夏", p.segments[0].text)
    }
}
