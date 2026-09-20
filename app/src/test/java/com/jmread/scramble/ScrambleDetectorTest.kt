package com.jmread.scramble

import com.jmread.core.scramble.ScrambleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 乱序判定与还原单测（纯 Kotlin，不依赖 Android）。
 *
 * 构造方式：真实图是「同一幅图的连续内容」，用带行号信息的渐变 + 细纹理模拟——
 * 相邻行颜色接近（真序接缝小），相距多块的行颜色差大（乱序接缝大）。
 * 灰度值 = row / 2（0..200 渐变）+ (row % 7) 微纹理。
 */
class ScrambleDetectorTest {

    private fun makeImage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val g = ((y / 2) + (y % 7)) and 0xFF
            for (x in 0 until w) {
                px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return px
    }

    /** 把 [pixels] 的横条按块数倒序重排（模拟服务端打乱） */
    private fun scramble(pixels: IntArray, w: Int, h: Int, splits: Int): IntArray =
        ScrambleDetector.restore(pixels, w, h, splits)

    @Test
    fun `十等分倒序的图被判定为乱序且可还原`() {
        val w = 64
        val h = 400
        val original = makeImage(w, h)
        val scrambled = scramble(original, w, h, 10)

        assertTrue(ScrambleDetector.isScrambled(scrambled, w, h))
        val (restored, hit) = ScrambleDetector.detectAndRestore(scrambled, w, h)
        assertTrue(hit)
        assertArrayEqualsPx(original, restored)
    }

    @Test
    fun `八等分倒序的图被判定为乱序且可还原`() {
        val w = 64
        val h = 320
        val original = makeImage(w, h)
        val scrambled = scramble(original, w, h, 8)

        val (restored, hit) = ScrambleDetector.detectAndRestore(scrambled, w, h)
        assertTrue(hit)
        assertArrayEqualsPx(original, restored)
    }

    @Test
    fun `正常图不被误翻`() {
        val w = 64
        val h = 400
        val original = makeImage(w, h)
        assertFalse(ScrambleDetector.isScrambled(original, w, h))
        val (same, hit) = ScrambleDetector.detectAndRestore(original, w, h)
        assertFalse(hit)
        assertArrayEqualsPx(original, same)
    }

    @Test
    fun `纯平坦图（接缝代价极低）不触发还原`() {
        val w = 64
        val h = 200
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        // 平坦图打乱后恒等代价高、倒序代价也是 0（所有条块相同）→ 双门第二门（绝对阈值）拦住
        val scrambled = scramble(flat, w, h, 10)
        assertFalse(ScrambleDetector.isScrambled(scrambled, w, h))
    }

    @Test
    fun `restore 保持总像素数不变`() {
        val w = 32
        val h = 100
        val px = makeImage(w, h)
        val out = ScrambleDetector.restore(px, w, h, 10)
        assertEquals(px.size, out.size)
    }

    private fun assertArrayEqualsPx(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("pixel mismatch at $i: ${expected[i]} != ${actual[i]}")
            }
        }
    }
}
