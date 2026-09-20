package com.jmread.core.scramble

import kotlin.math.abs

/**
 * 禁漫图片「十等分倒序」打乱的逐图判定与还原（纯 Kotlin，JVM 可测）。
 *
 * 实测依据（pika/reports/jm-source-design-20260919.html E6）：
 *  - 打乱按「图」生效，同一章内可以第 2 页乱、第 3~5 页正常 → 只能逐图判定，不能用 id 区间；
 *  - 算法为十等分倒序；56 页实测中乱序样本的倒序接缝代价比恒等低 5~14 倍；
 *  - 45 页正常样本双门全部不触发 → 双门阈值（相对 2×、绝对 20）足够保守。
 *
 * 成本：判定只需 10 组边界行的像素差（1280 宽 ≈ 1.3 万次比较），远低于一帧渲染。
 */
object ScrambleDetector {

    /** 候选等分数：实测线上为 10；社区库历史上有 8（421926+ 段），参数化预留 */
    val candidateSplits: List<Int> = listOf(10, 8)

    /** 双门阈值：倒序代价 < 恒等代价的一半，且恒等代价绝对值足够大 */
    const val RELATIVE_GATE = 2.0
    const val ABSOLUTE_GATE = 20.0

    /** ARGB 像素的亮度（只对边界行按需计算，不做全图灰度化） */
    private fun lum(px: Int): Int =
        ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000

    private fun blockStart(h: Int, splits: Int, block: Int): Int = (block.toLong() * h / splits).toInt()
    private fun blockEnd(h: Int, splits: Int, block: Int): Int = ((block + 1).toLong() * h / splits).toInt()

    /**
     * 按 [blockOrder]（从上到下的块下标序列）读图，求相邻块边界行的平均绝对差。
     * 恒等序 = 图片本来的接缝代价；倒序序低 = 图片被打乱过。
     */
    fun seamCost(pixels: IntArray, w: Int, h: Int, splits: Int, blockOrder: List<Int>): Double {
        if (w <= 0 || h < splits) return Double.MAX_VALUE
        var total = 0.0
        var n = 0
        for (k in 0 until splits - 1) {
            val a = blockOrder[k]
            val b = blockOrder[k + 1]
            val aBottom = blockEnd(h, splits, a) - 1
            val bTop = blockStart(h, splits, b)
            for (off in 0 until 2) {
                val ra = aBottom - off
                val rb = bTop + off
                if (ra < 0 || rb >= h) continue
                var s = 0L
                val rowA = ra * w
                val rowB = rb * w
                for (x in 0 until w) {
                    s += abs(lum(pixels[rowA + x]) - lum(pixels[rowB + x]))
                }
                total += s.toDouble() / w
                n++
            }
        }
        return if (n == 0) Double.MAX_VALUE else total / n
    }

    /**
     * 判定一张图是否被打乱。
     * @return true = 需要（并已验证值得）倒序还原
     */
    fun isScrambled(pixels: IntArray, w: Int, h: Int): Boolean {
        var bestRev = Double.MAX_VALUE
        for (splits in candidateSplits) {
            val identity = seamCost(pixels, w, h, splits, (0 until splits).toList())
            val reversed = seamCost(pixels, w, h, splits, (splits - 1 downTo 0).toList())
            if (reversed * RELATIVE_GATE < identity && identity >= ABSOLUTE_GATE) {
                // 取代价差最大的等分数对应的判定；10 等分优先（线上现行规则）
                if (reversed < bestRev) bestRev = reversed
            }
        }
        return bestRev != Double.MAX_VALUE
    }

    /**
     * 倒序重排：把 [splits] 等分的横条按 (N-1..0) 重排成还原后的像素。
     * 只在 [isScrambled] 为 true 时调用。
     */
    fun restore(pixels: IntArray, w: Int, h: Int, splits: Int = 10): IntArray {
        val out = IntArray(pixels.size)
        for (b in 0 until splits) {
            val srcStart = blockStart(h, splits, b)
            val srcEnd = blockEnd(h, splits, b)
            val dstStart = blockStart(h, splits, splits - 1 - b)
            System.arraycopy(pixels, srcStart * w, out, dstStart * w, (srcEnd - srcStart) * w)
        }
        return out
    }

    /** 判定 + 条件还原一步完成；未命中打乱时原样返回 [pixels]（不复制） */
    fun detectAndRestore(pixels: IntArray, w: Int, h: Int): Pair<IntArray, Boolean> {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return pixels to false
        var hitSplits = 0
        for (splits in candidateSplits) {
            val identity = seamCost(pixels, w, h, splits, (0 until splits).toList())
            val reversed = seamCost(pixels, w, h, splits, (splits - 1 downTo 0).toList())
            if (reversed * RELATIVE_GATE < identity && identity >= ABSOLUTE_GATE) {
                hitSplits = splits
                break
            }
        }
        return if (hitSplits > 0) restore(pixels, w, h, hitSplits) to true else pixels to false
    }
}
