package com.jmread.core.scramble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.jmread.core.log.PerfTrace
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 乱序处理的 Android 侧胶水：Bitmap/字节流 ↔ 条带搬运。
 *
 * 应用面（三处，存的都是「还原后的字节」）：
 *  - Coil 自定义 Fetcher（在线阅读，JmImageFetcher）
 *  - DownloadManager 落盘前（离线阅读）
 *  - 保存/分享（ImageSaver）
 *
 * 只有章节图（/media/photos/）会被打乱，封面不做处理。
 *
 * 峰值控制（PERF-01）：
 *  - 旧实现 `getPixels` + `restore` + `setPixels` 会同时持有两份全图 IntArray，
 *    叠上源/目标 Bitmap 可达单图预算的 4 倍；现在改为「源 Bitmap → 目标 Bitmap」
 *    的整数矩形条带搬运，不再分配全图像素数组；
 *  - 解码/还原/压缩整段包在 [ImageProcessingBudget] 的总预算里，跨阅读、预取、
 *    下载共享；单任务超预算时先降采样，仍超则明确失败，不排队等死；
 *  - 像素计算跑在受限 CPU 执行器，网络 IO 与处理分离；阶段之间检查取消。
 */
object ImageScrambler {

    /** 手动覆盖后缀：强制倒序还原（自动判定漏判时用户兜底） */
    const val SUFFIX_FORCE_RESTORE = "#m1"
    /** 手动覆盖后缀：强制保持原样（自动判定误翻时用户兜底） */
    const val SUFFIX_FORCE_RAW = "#m0"

    /** 手动覆盖表（内存态）：1=强制还原 / -1=强制原样；键为干净 URL */
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

    /** 该 URL 应使用的条数：`#m1` 强制还原时规则不适用的图回退 10 条 */
    private fun segmentsFor(url: String, force: Int?): Int {
        val ruleSegments = JmScramble.segmentCountForUrl(url)
        return if (force == 1 && ruleSegments <= 1) 10 else ruleSegments
    }

    /** 判定 + 条件还原。未命中打乱时原样返回 [bitmap]（不复制位图）。
     *  [force]：1=强制还原 / -1=强制原样 / null=自动判定（写入判定缓存） */
    suspend fun processIfNeeded(url: String, bitmap: Bitmap, force: Int? = null): Bitmap {
        if (!isPhotoUrl(url) || bitmap.width <= 0 || force == -1) return bitmap
        val segments = segmentsFor(url, force)
        if (force == null) ScrambleCache.put(verdictKey(url), segments > 1)
        if (segments <= 1 || bitmap.height < segments) return bitmap
        val estimate = ImageProcessingBudget.estimateBytes(bitmap.width, bitmap.height)
        return ImageProcessingBudget.withBudget(estimate) {
            withContext(ImageProcessingBudget.processingDispatcher()) {
                coroutineContext.ensureActive()
                restoreBitmap(bitmap, segments)
            }
        }
    }

    /**
     * 下载/阅读路径：解码 → 判定还原 → 重编码。
     * 已判定「未打乱」或未命中打乱时返回原始字节（零损失）；命中时重编码（minSdk 26 用兼容的 WEBP）。
     *
     * 整段（解码 + 条带搬运 + 压缩）共用一次预算申请；`Bitmap.compress` 无法中断，
     * 只承诺其返回后不再继续编码/缓存下一阶段。
     */
    suspend fun processBytesIfNeeded(
        url: String,
        bytes: ByteArray,
        force: Int? = null,
        maxWidth: Int = 2160,
        maxHeight: Int = 8192,
    ): ByteArray {
        if (!isPhotoUrl(url)) return bytes
        if (force == -1) return bytes
        if (force == null && JmScramble.segmentCountForUrl(url) <= 1) return bytes
        // 还原发生在 Coil 缩略解码之前：必须先按预算降采样，否则超宽/超长原图
        // 会在 decodeByteArray 阶段直接 OOM（解码上限只能约束 Coil 的第二次解码）。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return bytes

        // 输入压缩字节在整段处理期间一直驻留，必须计入同一份预算
        val available = (ImageProcessingBudget.total - bytes.size).coerceAtLeast(0L)
        val maxPixels = available / 8L
        if (maxPixels <= 0L) {
            throw ImageProcessingBudget.ExceededException(
                bytes.size.toLong(),
                ImageProcessingBudget.total,
            )
        }
        val sample = sampleSizeFor(
            width = srcW,
            height = srcH,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxPixels = maxPixels,
        )
        val outW = scaledDim(srcW, sample)
        val outH = scaledDim(srcH, sample)
        val bitmapEstimate = ImageProcessingBudget.estimateBytes(outW, outH)
        val estimate = if (bitmapEstimate > Long.MAX_VALUE - bytes.size) {
            Long.MAX_VALUE
        } else {
            bitmapEstimate + bytes.size
        }
        if (estimate > ImageProcessingBudget.total) {
            throw ImageProcessingBudget.ExceededException(estimate, ImageProcessingBudget.total)
        }

        return ImageProcessingBudget.withBudget(estimate) {
            withContext(ImageProcessingBudget.processingDispatcher()) {
                coroutineContext.ensureActive()
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: return@withContext bytes
                try {
                    coroutineContext.ensureActive()
                    val segments = segmentsFor(url, force)
                    if (force == null) ScrambleCache.put(verdictKey(url), segments > 1)
                    if (segments <= 1 || bmp.height < segments) return@withContext bytes
                    val processed = PerfTrace.section("image.restore") {
                        restoreBitmap(bmp, segments)
                    }
                    try {
                        coroutineContext.ensureActive()
                        val encoded = PerfTrace.section("image.encode") {
                            val bos = ByteArrayOutputStream(bytes.size.coerceAtLeast(64 * 1024))
                            if (!processed.compress(Bitmap.CompressFormat.WEBP, 92, bos)) {
                                throw IOException("图片还原后编码失败：$url")
                            }
                            bos.toByteArray()
                        }
                        coroutineContext.ensureActive()
                        encoded
                    } finally {
                        processed.recycle()
                    }
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /**
     * 条带搬运：源/目标矩形同尺寸且关闭滤波，逐像素一致。
     * 目标 Bitmap 与源 Bitmap 同时驻留，预算已按两份位图估算。
     */
    private fun restoreBitmap(src: Bitmap, segments: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            val paint = Paint().apply {
                isFilterBitmap = false
                isAntiAlias = false
                isDither = false
            }
            val srcRect = Rect()
            val dstRect = Rect()
            for (strip in JmScramble.restoreStrips(h, segments)) {
                srcRect.set(0, strip.srcY, w, strip.srcY + strip.rows)
                dstRect.set(0, strip.dstY, w, strip.dstY + strip.rows)
                canvas.drawBitmap(src, srcRect, dstRect, paint)
            }
            return out
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
    }

    /**
     * 2 的幂降采样：同时满足单图宽高上限与处理预算的单任务像素上限。
     * 用 ceil 计算解码后的真实尺寸（Android 解码器对 inSampleSize 向上取整）。
     */
    private fun sampleSizeFor(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
        maxPixels: Long,
    ): Int {
        var sample = 1
        while (sample < MAX_SAMPLE) {
            val w = scaledDim(width, sample)
            val h = scaledDim(height, sample)
            val overDim = w > maxWidth || h > maxHeight
            val overPixels = w.toLong() * h.toLong() > maxPixels
            if (!overDim && !overPixels) break
            sample *= 2
        }
        return sample
    }

    private fun scaledDim(value: Int, sample: Int): Int =
        ((value.toLong() + sample - 1L) / sample).coerceAtLeast(1L).toInt()

    private const val MAX_SAMPLE = 1 shl 20
}
