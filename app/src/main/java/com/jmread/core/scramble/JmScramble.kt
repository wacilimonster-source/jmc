package com.jmread.core.scramble

import java.security.MessageDigest

/**
 * 禁漫章节图的确定性乱序规则（与官方 App / 网页端 JS 一致，纯 Kotlin，JVM 可测）。
 *
 * 服务端把图片横切成 N 条后整体倒序存储，N 只由「章节 id + 文件名」决定：
 *  - id < [SCRAMBLE_ID]：未打乱；
 *  - id < [SPLIT_V2_ID]：固定 10 条；
 *  - 其余：md5(id + 文件名不含扩展名) 末字符的码值，
 *    id ≥ [SPLIT_V3_ID] 时 % 8，否则 % 10，再 ×2+2（2..20 条）。
 *
 * 旧实现靠接缝代价只试 10/8 两种条数，其它条数（2/4/6/12/14…）全部漏判，
 * 页面显示成一条条错位的横条。
 */
object JmScramble {
    const val SCRAMBLE_ID = 220980
    const val SPLIT_V2_ID = 268850
    const val SPLIT_V3_ID = 421926

    /** 从 `/media/photos/{id}/{file}` 解析章节 id 与文件名（不含扩展名），解析失败返回 null */
    fun parsePhotoUrl(url: String): Pair<Int, String>? {
        val marker = "/media/photos/"
        val i = url.indexOf(marker)
        if (i < 0) return null
        val rest = url.substring(i + marker.length).substringBefore('?').substringBefore('#')
        val id = rest.substringBefore('/').toIntOrNull() ?: return null
        val file = rest.substringAfter('/', "").substringAfterLast('/')
        if (file.isEmpty()) return null
        return id to file
    }

    /** 0 表示该图未打乱 */
    fun segmentCount(photoId: Int, fileName: String): Int {
        if (photoId < SCRAMBLE_ID) return 0
        if (photoId < SPLIT_V2_ID) return 10
        val stem = fileName.substringBeforeLast('.')
        val digest = MessageDigest.getInstance("MD5").digest("$photoId$stem".toByteArray())
        val last = "%02x".format(digest.last()).last().code
        val mod = if (photoId >= SPLIT_V3_ID) 8 else 10
        return 2 + (last % mod) * 2
    }

    /** 按 URL 计算条数；gif 不打乱，非章节图返回 0 */
    fun segmentCountForUrl(url: String): Int {
        val (id, file) = parsePhotoUrl(url) ?: return 0
        if (file.endsWith(".gif", ignoreCase = true)) return 0
        return segmentCount(id, file)
    }

    /**
     * 还原：存储图自下而上第 i 条（首条带余数行）放回目标图自上而下第 i 条。
     * 余数行归属与官方实现一致：目标首条高 base+rem，存储图余数在最底部那条。
     */
    fun restore(pixels: IntArray, w: Int, h: Int, segments: Int): IntArray {
        if (segments <= 1 || w <= 0 || h < segments) return pixels
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
}
