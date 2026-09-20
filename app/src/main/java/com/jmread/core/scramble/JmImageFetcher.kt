package com.jmread.core.scramble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.jmread.network.BcTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import java.io.IOException

/**
 * 图片缓存键：被打乱的章节图以 "#de" 后缀缓存「还原后」的字节/位图，
 * 与原始字节的缓存隔离（否则错乱版本会被缓存住——PiKA 实测的坑）。
 */
class JmUrlKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String = when {
        // 手动覆盖请求自带独立后缀，直接作 key（与自动判定结果缓存隔离）
        data.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) ||
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> data
        ImageScrambler.isPhotoUrl(data) -> "$data#de"
        else -> data
    }
}

/**
 * 自定义图片 Fetcher：章节图在解码前做乱序判定与还原。
 *
 * 注册后对所有 String URL 生效：封面等非 photo URL 原字节透传（行为等同默认网络
 * Fetcher，仅多一次内存缓冲，封面体积小可忽略）；photo URL 走「下载 → 判定 → 还原」，
 * 还原后的字节交给 Coil 解码并被磁盘缓存（key 带 #de，见 [JmUrlKeyer]）。
 *
 * 图片 CDN 无防盗链（实测裸请求 200），不需要额外 Referer/签名。
 */
class JmImageFetcher(
    private val data: String,
    private val options: Options,
) : Fetcher {

    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: coil.ImageLoader): Fetcher =
            JmImageFetcher(data, options)
    }

    override suspend fun fetch(): coil.fetch.FetchResult = withContext(Dispatchers.IO) {
        val force = when {
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> 1
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> -1
            else -> null
        }
        val cleanUrl = ImageScrambler.stripManualSuffix(data)
        val bytes = download(cleanUrl)
        val processed = if (ImageScrambler.isPhotoUrl(cleanUrl)) {
            ImageScrambler.processBytesIfNeeded(cleanUrl, bytes, force)
        } else {
            bytes
        }
        SourceResult(
            source = ImageSource(Buffer().apply { write(processed) }, options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.12.0")
            .build()
        BcTls.imageLoaderClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("图片加载失败 HTTP ${resp.code}: $url")
            return resp.body?.bytes() ?: throw IOException("图片响应为空: $url")
        }
    }
}
