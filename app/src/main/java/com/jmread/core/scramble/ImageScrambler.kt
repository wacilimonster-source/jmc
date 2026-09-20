package com.jmread.core.scramble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 乱序处理的 Android 侧胶水：Bitmap/字节流 ↔ 像素数组。
 *
 * 应用面（两处，存的都是「还原后的字节」）：
 *  - Coil 自定义 Fetcher（在线阅读，JmImageFetcher）
 *  - DownloadManager 落盘前（离线阅读）
 *
 * 只有章节图（/media/photos/）会被打乱，封面不做处理。
 * 判定结果有 ScrambleCache 记忆：已知「未打乱」的图免解码直接透传。
 */
object ImageScrambler {

    /** 手动覆盖后缀：强制倒序还原（自动判定漏判时用户兜底） */
    const val SUFFIX_FORCE_RESTORE = "#m1"
    /** 手动覆盖后缀：强制保持原样（自动判定误翻时用户兜底） */
    const val SUFFIX_FORCE_RAW = "#m0"

    // 手动覆盖表（内存态）：1=强制还原 / -1=强制原样；键为干净 URL
    private val manualOverride = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun setManualOverride(cleanUrl: String, value: Int?) {
        if (value == null || value == 0) manualOverride.remove(cleanUrl) else manualOverride[cleanUrl] = value
    }

    fun manualOverrideOf(cleanUrl: String): Int = manualOverride[cleanUrl] ?: 0

    /** 从带覆盖后缀的 URL 中剥出干净 URL（#m1 / #m0） */
    fun stripManualSuffix(data: String): String = when {
        data.endsWith(SUFFIX_FORCE_RESTORE) -> data.removeSuffix(SUFFIX_FORCE_RESTORE)
        data.endsWith(SUFFIX_FORCE_RAW) -> data.removeSuffix(SUFFIX_FORCE_RAW)
        else -> data
    }

    /** 是否是需要判定乱序的图片 URL */
    fun isPhotoUrl(url: String): Boolean = url.contains("/media/photos/")

    /** 判定缓存 key（photoId/文件名） */
    fun verdictKey(url: String): String {
        val marker = "/media/photos/"
        val i = url.lastIndexOf(marker)
        if (i < 0) return url
        return url.substring(i + marker.length)
    }

    /** 判定 + 条件还原。未命中打乱时原样返回 [bitmap]（不复制位图）。
     *  [force]：1=强制还原 / -1=强制原样 / null=自动判定（写入判定缓存） */
    fun processIfNeeded(url: String, bitmap: Bitmap, force: Int? = null): Bitmap {
        if (!isPhotoUrl(url) || bitmap.width <= 0 || bitmap.height < ScrambleDetector.candidateSplits.last()) {
            return bitmap
        }
        val key = verdictKey(url)
        if (force == null && ScrambleCache.get(key) == false) return bitmap
        if (force == -1) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (force == 1) {
            val restored = ScrambleDetector.restore(pixels, w, h, 10)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(restored, 0, w, 0, 0, w, h)
            return out
        }
        val (restored, scrambled) = ScrambleDetector.detectAndRestore(pixels, w, h)
        ScrambleCache.put(key, scrambled)
        if (!scrambled) return bitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(restored, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 下载路径：解码 → 判定还原 → 重编码。
     * 已判定「未打乱」或未命中打乱时返回原始字节（零损失）；命中时重编码（minSdk 26 用兼容的 WEBP）。
     */
    fun processBytesIfNeeded(url: String, bytes: ByteArray, force: Int? = null): ByteArray {
        if (!isPhotoUrl(url)) return bytes
        if (force == -1) return bytes
        val key = verdictKey(url)
        if (force == null && ScrambleCache.get(key) == false) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val processed = processIfNeeded(url, bmp, force)
        if (processed === bmp) {
            bmp.recycle()
            return bytes
        }
        val bos = ByteArrayOutputStream(bytes.size)
        processed.compress(Bitmap.CompressFormat.WEBP, 92, bos)
        processed.recycle()
        bmp.recycle()
        return bos.toByteArray()
    }
}
