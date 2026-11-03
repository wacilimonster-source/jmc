package com.jmread.scramble

import com.jmread.core.scramble.JmScramble
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-01：条带计划必须与旧的全图 arraycopy 实现逐像素一致。 */
class JmScrambleStripTest {

    private fun scramble(px: IntArray, w: Int, h: Int, n: Int): IntArray {
        val out = IntArray(px.size)
        val base = h / n
        val rem = h % n
        for (i in 0 until n) {
            var srcY = base * i
            var rows = base
            val dstY = h - base * (i + 1) - rem
            if (i == 0) rows += rem else srcY += rem
            System.arraycopy(px, srcY * w, out, dstY * w, rows * w)
        }
        return out
    }

    /** 旧实现（两份全图 IntArray）作为基准，条带计划必须完全等价。 */
    private fun legacyRestore(pixels: IntArray, w: Int, h: Int, segments: Int): IntArray {
        val out = IntArray(pixels.size)
        val base = h / segments
        val rem = h % segments
        for (i in 0 until segments) {
            var dstY = base * i
            var rows = base
            val srcY = h - base * (i + 1) - rem
            if (i == 0) rows += rem else dstY += rem
            System.arraycopy(pixels, srcY * w, out, dstY * w, rows * w)
        }
        return out
    }

    @Test
    fun `条带计划完整覆盖且不重叠`() {
        for (h in listOf(2, 3, 37, 1134, 1361, 4200)) {
            for (n in listOf(2, 4, 8, 10, 12, 14, 20)) {
                if (h < n) continue
                val strips = JmScramble.restoreStrips(h, n)
                assertEquals("h=$h n=$n 条带数", n, strips.size)
                val covered = BooleanArray(h)
                for (s in strips) {
                    assertTrue("h=$h n=$n srcY=${s.srcY}", s.srcY >= 0)
                    assertTrue("h=$h n=$n dstY=${s.dstY}", s.dstY >= 0)
                    assertTrue("h=$h n=$n rows=${s.rows}", s.rows > 0)
                    for (y in s.srcY until s.srcY + s.rows) {
                        assertTrue("h=$h n=$n 源行重复 $y", !covered[y])
                        covered[y] = true
                    }
                }
                assertTrue("h=$h n=$n 有源行未覆盖", covered.all { it })
            }
        }
    }

    @Test
    fun `条带还原与旧实现逐像素一致`() {
        val w = 3
        for (h in listOf(1134, 1361, 4200, 37, 20)) {
            for (n in listOf(2, 4, 10, 14, 20)) {
                if (h < n) continue
                val original = IntArray(w * h) { it }
                val scrambled = scramble(original, w, h, n)
                assertArrayEquals(
                    "h=$h n=$n",
                    legacyRestore(scrambled, w, h, n),
                    JmScramble.restore(scrambled, w, h, n),
                )
            }
        }
    }

    @Test
    fun `无需还原时原样返回`() {
        val pixels = IntArray(12) { it }
        assertSame(pixels, JmScramble.restore(pixels, 3, 4, 1))
        assertSame(pixels, JmScramble.restore(pixels, 3, 4, 5))
        assertTrue(JmScramble.restoreStrips(4, 1).isEmpty())
        assertTrue(JmScramble.restoreStrips(4, 5).isEmpty())
    }
}
