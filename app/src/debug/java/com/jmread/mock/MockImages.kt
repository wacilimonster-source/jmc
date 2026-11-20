package com.jmread.mock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/**
 * 模拟图片字节生成（debug 专用）。
 *
 *  - 封面（/media/albums/）：480×640，色块 + 「模拟封面 #id」文字；*_3x4.jpg 高清版同理。
 *  - 章节图（/media/photos/）：720×1080 纯平滑渐变 —— 不画文字/硬边，
 *    避免 ScrambleDetector 缝隙判定误报（章节 photoId 已按 <220980 选定，不触发还原，
 *    但判定路径仍可能在低 id 上运行）。
 *  - 头像/静态资源（mock.jm.invalid）：96×96 纯色。
 *
 * 全部确定性：同一 URL 永远生成同一张图，磁盘缓存/预取/合流的缓存键语义不受影响。
 */
object MockImages {

    private val cache = HashMap<String, ByteArray>()
    private const val MOCK_HOST_PREFIX = "https://mock.jm.invalid"

    /** JmImageFetcher 挂载点：只接管禁漫 /media 图片 URL */
    suspend fun provide(url: String): ByteArray? {
        if (!MockState.enabled) return null
        val isPhoto = url.contains("/media/photos/")
        val isAlbum = url.contains("/media/albums/")
        if (!isPhoto && !isAlbum) return null
        // 故障注入同样作用于图片路径：断网=抛 IOException（走真实失败态/重试覆盖层），
        // 慢网=延迟后成功。否则封面永远成功，故障注入只覆盖 API 不覆盖图片，保真度不足。
        when (MockState.fault) {
            MockFault.OFFLINE -> throw java.io.IOException("模拟故障注入：断网（jm-mock 图片）")
            MockFault.SLOW -> kotlinx.coroutines.delay(2_000)
            MockFault.ERROR500, MockFault.TRUNCATE, MockFault.NONE -> Unit
        }
        synchronized(cache) { cache[url]?.let { return it } }
        val bytes = if (isPhoto) photoBytes(url) else coverBytes(url)
        synchronized(cache) { if (cache.size < 256) cache[url] = bytes }
        return bytes
    }

    /** Coil 挂载点：mock.jm.invalid 的头像/静态资源占位图 */
    fun generic(url: String): ByteArray {
        val key = "generic:$url"
        synchronized(cache) { cache[key]?.let { return it } }
        val bytes = solidBytes(seed = url.hashCode(), size = 96)
        synchronized(cache) { if (cache.size < 256) cache[key] = bytes }
        return bytes
    }

    /** 封面：色块 + id 文字（封面路径不参与乱序还原，可安全画文字） */
    private fun coverBytes(url: String): ByteArray {
        val id = url.substringAfterLast('/').substringBefore('_').substringBefore('.')
        val w = 480
        val h = 640
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c1 = colorFrom(id, 0)
        val c2 = colorFrom(id, 1)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("模拟封面", w / 2f, h / 2f - 10f, text)
        canvas.drawText("#$id", w / 2f, h / 2f + 44f, text)
        return encode(bmp)
    }

    /** 章节图：长条比例 3:10（纯平滑渐变，无文字/硬边，规避缝隙误判），模拟真实条漫形态 */
    private fun photoBytes(url: String): ByteArray {
        // URL 形如 https://host/media/photos/{photoId}/{filename}
        val page = url.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: 1
        val id = url.substringAfter("/media/photos/").substringBefore('/')
        val w = 600
        val h = 2000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c1 = colorFrom(id, page)
        val c2 = colorFrom(id, page + 7)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return encode(bmp)
    }

    private fun solidBytes(seed: Int, size: Int): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(colorFrom(seed.toString(), seed and 0x3))
        return encode(bmp)
    }

    private fun colorFrom(seed: String, salt: Int): Int {
        var h = salt.toLong() * 0x9E3779B9L
        seed.forEach { c -> h = h * 31 + c.code }
        return Color.rgb(
            (((h ushr 16) and 0x7F).toInt()) + 80,
            (((h ushr 8) and 0x7F).toInt()) + 80,
            ((h and 0x7F).toInt()) + 80,
        )
    }

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
