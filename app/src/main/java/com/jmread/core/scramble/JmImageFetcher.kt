package com.jmread.core.scramble

import android.graphics.Bitmap
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.jmread.core.MemoryBudget
import com.jmread.core.log.LogStore
import com.jmread.core.log.PerfTrace
import com.jmread.core.netconfig.DomainPool
import com.jmread.network.BcTls
import com.jmread.network.withCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
internal fun jmImageCacheKey(url: String): String {
    val mode = when {
        url.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> ImageScrambler.SUFFIX_FORCE_RESTORE
        url.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> ImageScrambler.SUFFIX_FORCE_RAW
        else -> ""
    }
    // 重试/预取后缀只用于破“同一请求”的即时状态，不进入持久缓存身份；
    // 否则每次重试都会生成新磁盘缓存，且预取与展示永远命中不到同一份结果。
    val base = ImageScrambler.stripManualSuffix(url)
        .substringBefore("#retry")
        .substringBefore(PREFETCH_MARKER)
    return when {
        !ImageScrambler.isPhotoUrl(base) -> base
        mode == ImageScrambler.SUFFIX_FORCE_RAW -> "$base#raw"
        mode == ImageScrambler.SUFFIX_FORCE_RESTORE -> "$base#de2#m1"
        // #de2：旧版 #de 缓存里存的是按错误条数「还原」过的字节，换键让它们自然失效
        else -> "$base#de2"
    }
}

/** 预取标记：只影响调度优先级，不进入缓存身份，也不发给 CDN。 */
internal const val PREFETCH_MARKER = "#prefetch"

/** 剥掉重试/预取标记与手动后缀，得到真正请求的干净 URL。 */
internal fun jmCleanUrl(data: String): String =
    ImageScrambler.stripManualSuffix(data)
        .substringBefore("#retry")
        .substringBefore(PREFETCH_MARKER)

/** 给 URL 追加预取标记；手动后缀必须留在最后（Fetcher 靠 endsWith 识别）。 */
internal fun jmPrefetchUrl(url: String): String {
    val manual = when {
        url.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> ImageScrambler.SUFFIX_FORCE_RESTORE
        url.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> ImageScrambler.SUFFIX_FORCE_RAW
        else -> ""
    }
    val base = url.removeSuffix(manual)
    return "$base$PREFETCH_MARKER$manual"
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
 * 资源护栏（PERF-02）：
 *  - 网络走 [com.jmread.network.withCall]（enqueue + 取消绑定），不再用同步 `execute()`；
 *  - 章节图按「可见 / 预取」进入 [ImageWorkScheduler]，封面不占阅读器配额；
 *  - 网络配额在拿到字节后立即释放，像素处理另走 [ImageProcessingBudget]；
 *  - 每次实际传输都消耗 [ImageRetryBudget]，换 CDN 不再额外获得预算。
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

    override suspend fun fetch(): coil.fetch.FetchResult {
        val force = when {
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> 1
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> -1
            else -> null
        }
        val cleanUrl = jmCleanUrl(data)
        val cacheKey = jmImageCacheKey(data)
        // 手动重试（#retryN）与管线级 WRITE_ONLY 重试都必须绕过磁盘缓存：
        // key 刻意不含 retry 计数，若仍先读缓存，坏字节永远无法被重新下载覆盖。
        val bypassDisk = data.contains("#retry") || !options.diskCachePolicy.readEnabled
        val cached = if (bypassDisk) {
            null
        } else {
            withContext(Dispatchers.IO) { readFromDisk(cacheKey) }
        }
        val (processed, source) = if (cached != null) {
            cached to DataSource.DISK
        } else {
            val priority = if (data.contains(PREFETCH_MARKER)) {
                ImageWorkScheduler.Priority.PREFETCH
            } else {
                ImageWorkScheduler.Priority.VISIBLE
            }
            val bytes = download(cleanUrl, priority, cacheKey, manualRetry = data.contains("#retry"))
            // 2xx 也可能是风控页/半截响应；非图片字节绝不能落盘，否则重试也会命中同一份坏缓存
            if (!ImageFormatDetector.isImage(bytes)) {
                throw IOException("图片响应不是有效图片（${bytes.size} bytes）: $cleanUrl")
            }
            // 网络配额已在此前释放；还原/编码进入处理预算与受限 CPU 执行器
            val out = if (ImageScrambler.isPhotoUrl(cleanUrl)) {
                ImageScrambler.processBytesIfNeeded(
                    cleanUrl,
                    bytes,
                    force,
                    maxHeight = MemoryBudget.maxDecodeHeightPx(options.context),
                )
            } else {
                bytes
            }
            if (options.diskCachePolicy.writeEnabled) {
                withContext(Dispatchers.IO) { writeToDisk(cacheKey, out) }
            }
            out to DataSource.NETWORK
        }
        return SourceResult(
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
     */
    private suspend fun download(
        url: String,
        priority: ImageWorkScheduler.Priority,
        cacheKey: String,
        manualRetry: Boolean,
    ): ByteArray = fetchJmImageBytes(
        url = url,
        priority = priority,
        budgetKey = cacheKey,
        manualRetry = manualRetry,
        // 章节图占阅读器配额；封面（/media/albums/）体积小、数量多，不挤占可见页
        useScheduler = ImageScrambler.isPhotoUrl(url),
    )
}


/**
 * 下载单张禁漫图片原始字节（带 CDN 换线 + 统一重试预算）；供保存/分享复用同一线路策略。
 *
 * 每次实际传输消耗同一份 [ImageRetryBudget]；取消立即放行且不标记 CDN 故障。
 */
internal suspend fun fetchJmImageBytes(
    url: String,
    priority: ImageWorkScheduler.Priority = ImageWorkScheduler.Priority.VISIBLE,
    budgetKey: String = jmImageCacheKey(url),
    manualRetry: Boolean = false,
    useScheduler: Boolean = true,
): ByteArray = withContext(Dispatchers.IO) {
    val parsed = url.toHttpUrlOrNull() ?: throw IOException("非法图片 URL: $url")
    val pathAndQuery = parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
    // 手动重试是一次新的用户意图：清空旧预算，重新获得 4 次传输
    if (manualRetry) ImageRetryLedger.clear(budgetKey)
    val budget = ImageRetryLedger.budgetFor(budgetKey)
    val hosts = DomainPool.imageHostOrder(parsed.host)
    var lastError: Exception? = null
    var attempt = 0
    while (budget.remainingTransports() > 0 && !budget.expired()) {
        val host = hosts[attempt % hosts.size]
        attempt++
        currentCoroutineContext().ensureActive()
        if (!budget.tryConsumeTransport()) break
        try {
            val bytes = PerfTrace.asyncSection("image.queue") {
                if (useScheduler) {
                    ImageWorkScheduler.withSlot(priority, host) {
                        PerfTrace.asyncSection("image.fetch") {
                            fetchJmImageOnce("https://$host$pathAndQuery")
                        }
                    }
                } else {
                    PerfTrace.asyncSection("image.fetch") {
                        fetchJmImageOnce("https://$host$pathAndQuery")
                    }
                }
            }
            // CDN 也可能返回 200 + HTML 风控页
            if (!ImageFormatDetector.isImage(bytes)) {
                throw IOException("图片响应不是有效图片（${bytes.size} bytes）")
            }
            DomainPool.markImageGood(host)
            // 不在这里清空预算：解码/响应体阶段仍可能失败，管线重试必须继续消耗
            // 同一份预算（最多 4 次实际传输）。手动重试会显式 clear 获得新预算。
            return@withContext bytes
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageWorkScheduler.PrefetchDisabledException) {
            // 低内存档预取关闭：直接交还，不当作 CDN 故障
            throw e
        } catch (e: Exception) {
            lastError = e
            val deterministic404 = e is ImageHttpException && e.code == 404
            // 404 是内容级确定性失败，不是 CDN 故障：不能把健康线路打入回避期
            if (!deterministic404) DomainPool.markImageBad(host)
            LogStore.log(
                "jm-img", "WARN",
                "图片 CDN $host 失败，换下一条线路：${e.message?.take(70)}",
            )
            when {
                // 确定性 404：换 host/重试都不会变
                deterministic404 -> break
                // 429 尊重 Retry-After，但绝不超过剩余 deadline
                e is ImageHttpException && e.code == 429 -> {
                    val wait = e.retryAfterMs ?: 0L
                    if (wait > 0L) delay(minOf(wait, budget.remainingMs()))
                }
            }
        }
    }
    throw lastError ?: IOException("图片加载失败（重试预算已用尽）: $url")
}

private suspend fun fetchJmImageOnce(url: String): ByteArray {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "okhttp/4.12.0")
        .build()
    return BcTls.imageLoaderClient.withCall(request) { resp ->
        if (!resp.isSuccessful) {
            throw ImageHttpException(
                code = resp.code,
                retryAfterMs = parseRetryAfterMs(resp.header("Retry-After")),
                message = "图片加载失败 HTTP ${resp.code}: $url",
            )
        }
        resp.readBodyBytesCapped()
    }
}
