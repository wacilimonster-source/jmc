package com.jmread.core.scramble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.jmread.core.log.LogStore
import com.jmread.core.netconfig.DomainPool
import com.jmread.network.BcTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.Buffer
import okio.FileSystem
import java.io.IOException

/**
 * 图片缓存键：被打乱的章节图以 "#de" 后缀缓存「还原后」的字节/位图，
 * 与原始字节的缓存隔离（否则错乱版本会被缓存住——PiKA 实测的坑）。
 *
 * ⚠ 必须注册为 [Keyer]<[Uri]> 而不是 Keyer<String>：
 *   Coil 2.7 在挑选 Fetcher/Keyer **之前** 会先用默认 StringMapper 把 String 映射成 Uri，
 *   按 String 注册的自定义组件永远不会命中（0.1.7 及以前的「乱序还原失效」根因）。
 */
class JmUrlKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String = jmImageCacheKey(data.toString())
}

/**
 * 图片缓存键：章节图（被打乱）用 "#de" 后缀缓存还原后的字节；
 * 手动覆盖请求保留自身后缀；封面等其它 URL 用原 URL。
 *
 * 预取（ReaderViewModel.preloadNearby）必须使用同一个 key，
 * 否则预取的字节会落在另一个缓存键下、展示请求无法命中。
 */
internal fun jmImageCacheKey(url: String): String = when {
    // 手动覆盖请求自带独立后缀，直接作 key（与自动判定结果缓存隔离）
    url.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) ||
        url.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> url
    ImageScrambler.isPhotoUrl(url) -> "$url#de"
    else -> url
}

/**
 * 是否属于本 App 需要接管的禁漫图片 URL。
 *
 * 只接管章节图与封面：这两个路径需要乱序还原 / CDN 换线；
 * 头像、外链图等其它 URL 必须返回 false，交给 Coil 默认实现按原 host 加载
 * （否则会被误改写到图片 CDN host，头像等非 /media 路径会 404）。
 */
internal fun isJmImageUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    val path = parsed.encodedPath
    return path.contains("/media/photos/") || path.contains("/media/albums/")
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
    /** 自定义 Fetcher 不会走 HttpUriFetcher 的磁盘缓存，必须自己读写 Coil DiskCache */
    private val diskCache: DiskCache?,
) : Fetcher {

    /**
     * 注册类型必须是 [Uri]（Coil 映射后的实际数据类型），见 [JmUrlKeyer] 的注释。
     * 非禁漫图片路径返回 null，让默认 HttpUriFetcher / FileUriFetcher 接管。
     */
    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!isJmImageUrl(url)) return null
            return JmImageFetcher(url, options, imageLoader.diskCache)
        }
    }

    override suspend fun fetch(): coil.fetch.FetchResult = withContext(Dispatchers.IO) {
        val force = when {
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> 1
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> -1
            else -> null
        }
        val cleanUrl = ImageScrambler.stripManualSuffix(data)
        val cacheKey = jmImageCacheKey(data)
        val cached = readFromDisk(cacheKey)
        val (processed, source) = if (cached != null) {
            cached to DataSource.DISK
        } else {
            val bytes = download(cleanUrl)
            val out = if (ImageScrambler.isPhotoUrl(cleanUrl)) {
                // 章节图判定乱序并还原后再落盘/解码（离线与在线必须一致）
                ImageScrambler.processBytesIfNeeded(cleanUrl, bytes, force)
            } else {
                bytes
            }
            writeToDisk(cacheKey, out)
            out to DataSource.NETWORK
        }
        SourceResult(
            source = ImageSource(Buffer().apply { write(processed) }, options.context),
            mimeType = null,
            dataSource = source,
        )
    }

    /** 读 Coil 磁盘缓存；未命中/损坏返回 null（缓存损坏不能阻断图片加载） */
    private fun readFromDisk(key: String): ByteArray? {
        val cache = diskCache ?: return null
        return runCatching {
            cache.openSnapshot(key)?.use { snap ->
                FileSystem.SYSTEM.read(snap.data) { readByteArray() }.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** 写 Coil 磁盘缓存（key 与展示请求一致；metadata 允许为空） */
    private fun writeToDisk(key: String, bytes: ByteArray) {
        val cache = diskCache ?: return
        runCatching {
            val editor = cache.openEditor(key) ?: return
            try {
                FileSystem.SYSTEM.write(editor.data) { write(bytes) }
                FileSystem.SYSTEM.write(editor.metadata) { write(ByteArray(0)) }
                editor.commit()
            } catch (e: Exception) {
                runCatching { editor.abort() }
                throw e
            }
        }.onFailure {
            LogStore.log("jm-img", "W", "图片磁盘缓存写入失败：${it.message?.take(70)}")
        }
    }

    /**
     * 下载图片：失败时换下一个图片 CDN 重试。
     *
     * 图片 CDN 与 API 是两套域名（API 走 www.cdngwc.*，图片走 cdn-msp*），
     * 故障互不相关；而 /setting 下发的 img_host 每次请求都可能不同，
     * 各 CDN 又会按区域被 DNS/运营商屏蔽（实测本机 *.jmapiproxy3.cc 被解析到 127.0.0.1）。
     * 早期版本只请求一个 host，失败即报「加载失败」，整本书的图永不恢复。
     */
    private fun download(url: String): ByteArray {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("非法图片 URL: $url")
        // 各 CDN 的路径完全一致（/media/photos/{id}/{file}），只换 host 即可
        val pathAndQuery = parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
        var lastError: Exception? = null
        for (host in DomainPool.imageHostOrder(parsed.host)) {
            try {
                val bytes = fetchOnce("https://$host$pathAndQuery")
                DomainPool.markImageGood(host)
                return bytes
            } catch (e: Exception) {
                lastError = e
                DomainPool.markImageBad(host)
                LogStore.log(
                    "jm-img", "WARN",
                    "图片 CDN $host 失败，换下一条线路：${e.message?.take(70)}",
                )
            }
        }
        throw lastError ?: IOException("图片加载失败（所有 CDN 均不可用）: $url")
    }

    private fun fetchOnce(url: String): ByteArray {
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
