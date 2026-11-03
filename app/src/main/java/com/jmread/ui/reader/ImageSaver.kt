package com.jmread.ui.reader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jmread.core.scramble.ImageFormat
import com.jmread.core.scramble.ImageFormatDetector
import com.jmread.core.MemoryBudget
import com.jmread.core.scramble.ImageScrambler
import com.jmread.core.scramble.fetchJmImageBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存/分享单页图片（P1-04）。
 *
 * 与 PiKA 的关键差异：JM 的章节图可能是「十等分倒序」打乱的，保存前必须走
 * [ImageScrambler] 还原；本地已下载文件本身已是还原后字节，自动模式直接复用。
 * 强制模式（#m1/#m0）不得对已还原文件重复处理：本地页无法反推原始字节时明确报错，
 * 提示用户联网重试。
 */
object ImageSaver {

    private val shareFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 读取并还原一张阅读页。imageUrl 可带 #m1/#m0 手动模式。 */
    suspend fun loadPageBytes(context: Context, imageUrl: String): ByteArray {
        val force = when {
            imageUrl.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> 1
            imageUrl.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> -1
            else -> null
        }
        if (imageUrl.startsWith("file:")) {
            if (force != null) {
                throw IOException("本地页已还原，无法应用强制翻转；请联网重试")
            }
            return withContext(Dispatchers.IO) {
                runCatching { File(URI(imageUrl)).readBytes() }
                    .getOrElse { throw IOException("读取本地图片失败：${it.message}") }
            }
        }
        val cleanUrl = ImageScrambler.stripManualSuffix(imageUrl).substringBefore("#retry")
        val raw = fetchJmImageBytes(cleanUrl)
        // 还原/编码自带处理预算与受限 CPU 执行器，这里不再包一层 Dispatchers.Default
        return ImageScrambler.processBytesIfNeeded(
            cleanUrl,
            raw,
            force,
            maxHeight = MemoryBudget.maxDecodeHeightPx(context),
        )
    }

    /** 按文件魔数识别真实编码，扩展名/MIME 不依赖下载文件名。 */
    fun detectFormat(bytes: ByteArray): ImageFormat = ImageFormatDetector.detect(bytes)

    /** 保存到相册，返回显示名。 */
    suspend fun saveToGallery(
        context: Context,
        imageUrl: String,
        comicTitle: String,
        chapterTitle: String,
        pageNumber: Int,
    ): String = withContext(Dispatchers.IO) {
        val bytes = loadPageBytes(context, imageUrl)
        val format = detectFormat(bytes)
        val name = fileName(comicTitle, chapterTitle, pageNumber, format.extension)
        if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(context, bytes, format, comicTitle, name)
        } else {
            saveLegacy(context, bytes, format, comicTitle, name)
        }
        name
    }

    /** 写入分享缓存，返回可交给 FileProvider 的文件。 */
    suspend fun shareToCache(
        context: Context,
        imageUrl: String,
        comicTitle: String,
        chapterTitle: String,
        pageNumber: Int,
    ): File = withContext(Dispatchers.IO) {
        cleanupShareCache(context)
        val bytes = loadPageBytes(context, imageUrl)
        val format = detectFormat(bytes)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val stamp = shareFormatter.format(Date())
        val file = File(dir, "${sanitize(comicTitle)}_${sanitize(chapterTitle)}_P$pageNumber-$stamp.${format.extension}")
        file.writeBytes(bytes)
        file
    }

    /** 清理超过 24h 的分享缓存；接收方读取后仍有一整天窗口。 */
    fun cleanupShareCache(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "share")
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        bytes: ByteArray,
        format: ImageFormat,
        comicTitle: String,
        displayName: String,
    ) {
        val resolver = context.contentResolver
        val relative = "${Environment.DIRECTORY_PICTURES}/JM Reader/${sanitize(comicTitle).take(40)}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relative)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册记录")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("无法写入相册")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        bytes: ByteArray,
        format: ImageFormat,
        comicTitle: String,
        displayName: String,
    ) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "JM Reader/${sanitize(comicTitle).take(40)}",
        ).apply { mkdirs() }
        if (!dir.isDirectory) throw IOException("无法创建相册目录")
        var target = File(dir, displayName)
        val base = displayName.substringBeforeLast('.')
        var index = 1
        while (target.exists()) {
            target = File(dir, "${base}_$index.${format.extension}")
            index++
        }
        target.writeBytes(bytes)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(format.mimeType),
            null,
        )
    }

    private fun fileName(
        comicTitle: String,
        chapterTitle: String,
        pageNumber: Int,
        extension: String,
    ): String {
        val title = sanitize(comicTitle).take(50).ifBlank { "JM" }
        val chapter = sanitize(chapterTitle).take(40).ifBlank { "第${pageNumber}页" }
        return "${title}_${chapter}_P$pageNumber.$extension"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\n\r\\x00-\\x1f]"), "_").trim().ifBlank { "JM" }
}
