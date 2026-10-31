package com.jmread.scramble

import com.jmread.core.scramble.JmScramble
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JmScrambleTest {

    /** 期望值与真实 CDN 原图人工核对过（还原后画面连续） */
    @Test
    fun `条数与线上图片一致`() {
        assertEquals(14, JmScramble.segmentCount(432596, "00020.webp"))
        assertEquals(4, JmScramble.segmentCount(432596, "00002.webp"))
        assertEquals(8, JmScramble.segmentCount(1114751, "00016.webp"))
        assertEquals(2, JmScramble.segmentCount(1114751, "00003.webp"))
        assertEquals(12, JmScramble.segmentCount(1114751, "00006.webp"))
    }

    @Test
    fun `按 id 区间的固定规则`() {
        assertEquals(0, JmScramble.segmentCount(220979, "00001.jpg"))
        assertEquals(10, JmScramble.segmentCount(250000, "00001.jpg"))
    }

    @Test
    fun `URL 解析与 gif 跳过`() {
        val url = "https://cdn-msp3.jmdanjonproxy.vip/media/photos/432596/00020.webp#retry2#m1"
        assertEquals(432596 to "00020.webp", JmScramble.parsePhotoUrl(url))
        assertEquals(14, JmScramble.segmentCountForUrl(url))
        assertEquals(0, JmScramble.segmentCountForUrl("https://x/media/photos/432596/00020.gif"))
        assertEquals(0, JmScramble.segmentCountForUrl("https://x/media/albums/432596.jpg"))
    }

    /** 服务端打乱：目标图自上而下第 i 条（首条带余数）写到存储图自下而上第 i 条 */
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

    @Test
    fun `还原是打乱的逆运算（含余数行）`() {
        val w = 3
        for (h in listOf(1134, 1361, 4200, 37)) {
            for (n in listOf(2, 4, 10, 14, 20)) {
                if (h < n) continue
                val original = IntArray(w * h) { it }
                val restored = JmScramble.restore(scramble(original, w, h, n), w, h, n)
                assertArrayEquals("h=$h n=$n", original, restored)
            }
        }
    }
}
