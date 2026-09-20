package com.jmread.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.jmread.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CacheControl
import com.jmread.network.BcTls
import com.jmread.core.log.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用更新：从 GitHub 仓库的 update.json 检查版本，下载 APK 并安装。
 *
 * update.json 结构：{ "version": "0.3.0", "apkUrl": "https://raw.githubusercontent.com/.../pika-v0.3.0.apk", "notes": "更新说明" }
 */
object UpdateManager {

    /** 与仓库 update.json 保持一致 */
    const val UPDATE_URL =
        "https://raw.githubusercontent.com/wacilimonster-source/pika/main/update.json"

    @Serializable
    data class UpdateInfo(
        val version: String = "",
        val apkUrl: String = "",
        val notes: String = "",
        /** 安装包 SHA-256（仓库 update.json 提供时启用强校验，防止 CDN 劫持安装被篡改的包） */
        val sha256: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        // TLS 由 PiKAApp.onCreate 单点安装；此处仅校验并记录，不再重复 install
        if (!BcTls.isAvailable()) BcTls.install()
        BcTls.applyTo(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
        ).build()
    }

    // ── 下载状态机（app 级作用域）────────────────────────────────────
    // 下载必须活过设置页的组合生命周期：此前挂在页面 rememberCoroutineScope 上，
    // 下载中离开设置页会被静默取消、无任何提示（回来状态归零）。状态收进
    // UpdateManager 后，关对话框/离开页面进度都不丢，回来自动续显、可重试。
    sealed interface DownloadUi {
        data object Idle : DownloadUi
        data class Downloading(
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadUi
        data class Done(val apk: File) : DownloadUi
        data class Failed(val message: String) : DownloadUi
    }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadUi = MutableStateFlow<DownloadUi>(DownloadUi.Idle)
    val downloadUi: StateFlow<DownloadUi> = _downloadUi.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * 启动（或重试）APK 下载 + 校验；已在下载中时忽略重复触发。
     * autoInstall=false 供更新横幅等"手动点安装"的入口使用。
     * 所有更新入口（设置页/首页横幅）必须共用本状态机：各自为政会产出两条
     * 并发下载流，对同一个 APK 文件交叉写入（SHA-256 兜底会拦下坏包，但下载必失败）。
     */
    fun startDownload(context: Context, info: UpdateInfo, autoInstall: Boolean = true) {
        if (_downloadUi.value is DownloadUi.Downloading) return
        downloadJob?.cancel()
        val appContext = context.applicationContext
        downloadJob = downloadScope.launch {
            _downloadUi.value = DownloadUi.Downloading(0f, 0L, -1L)
            try {
                val apk = downloadAndVerify(appContext, info) { p, done, total ->
                    _downloadUi.value = DownloadUi.Downloading(p, done, total)
                }
                _downloadUi.value = DownloadUi.Done(apk)
                if (autoInstall) install(appContext, apk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _downloadUi.value = DownloadUi.Failed(e.message ?: "下载失败")
            }
        }
    }

    /** 丢弃非下载中的历史状态（新一轮检查到更新时调用，让位给新的检查结果） */
    fun resetDownload() {
        if (_downloadUi.value is DownloadUi.Downloading) return
        downloadJob?.cancel()
        _downloadUi.value = DownloadUi.Idle
    }

    /**
     * 由 raw.githubusercontent.com 直链生成候选下载源（依次尝试）：
     * 1. 原 GitHub raw 直链
     * 2. jsDelivr CDN（国内访问通常更快）
     */
    private fun candidateUrls(apkUrl: String): List<String> {
        val urls = mutableListOf(apkUrl)
        val m = Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)")
            .find(apkUrl)
        if (m != null) {
            val (owner, repo, branch, file) = m.destructured
            urls += "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$file"
        }
        return urls
    }

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    /**
     * 检查更新结果：区分"已是最新"与"检查失败"。
     */
    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    /**
     * 检查更新：拉取远端信息并对比版本号。
     */
    suspend fun checkResult(): CheckResult = withContext(Dispatchers.IO) {
        // 网络失败与"更新信息格式错误"分开归因：update.json 损坏时报"网络异常"会误导排障
        val text = try {
            val urlWithTs = "$UPDATE_URL?ts=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(urlWithTs)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext CheckResult.Failed("网络异常或服务器未就绪（HTTP ${resp.code}）")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CheckResult.Failed("网络异常或服务器未就绪")
        }
        val info = try {
            json.decodeFromString(UpdateInfo.serializer(), text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext CheckResult.Failed("更新信息格式错误（update.json 损坏）")
        }

        if (info.version.isBlank() || info.apkUrl.isBlank()) {
            return@withContext CheckResult.Failed("远端更新信息不完整")
        }
        if (!isNewer(info.version, currentVersionName)) return@withContext CheckResult.UpToDate
        CheckResult.Available(info)
    }

    /**
     * 下载 APK 并校验完整性。
     * onProgress(progress, downloadedBytes, totalBytes)：totalBytes<=0 表示总长未知，
     * 此时 progress 为负数，调用方应按"已下载字节数"展示。
     * 依次尝试多个下载源（GitHub raw → jsDelivr CDN），单个源失败自动切换。
     */
    suspend fun download(context: Context, url: String, onProgress: (Float, Long, Long) -> Unit): File {
        var lastError: Exception? = null
        for (candidate in candidateUrls(url)) {
            try {
                return downloadFrom(context, candidate, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消/作用域销毁：立即停止，绝不继续换源重试
                throw e
            } catch (e: Exception) {
                lastError = e
                // 切下一个源前稍等，避免瞬时失败连续重试
                kotlinx.coroutines.delay(500)
            }
        }
        throw lastError ?: java.io.IOException("下载失败：无可用下载源")
    }

    /**
     * 下载 + SHA-256 校验的完整流程：update.json 提供 sha256 时强校验
     * （不一致即删除安装包并中止），未提供时仅记录实际哈希便于人工核对/回填。
     */
    suspend fun downloadAndVerify(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): File {
        val apk = download(context, info.apkUrl, onProgress)
        val actual = sha256(apk)
        val expected = info.sha256.trim()
        if (expected.isNotBlank()) {
            if (!actual.equals(expected, ignoreCase = true)) {
                apk.delete()
                LogStore.log("UpdateManager", "E", "apk sha256 mismatch: expected=$expected actual=$actual")
                throw java.io.IOException("安装包校验失败，已终止安装")
            }
        } else {
            LogStore.log("UpdateManager", "I", "apk sha256=$actual (update.json 未提供，仅供核对)")
        }
        return apk
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun downloadFrom(context: Context, url: String, onProgress: (Float, Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw java.io.IOException("下载失败：HTTP ${resp.code}")
                }
                val body = resp.body ?: throw java.io.IOException("下载失败：空响应")
                val total = body.contentLength()
                // filesDir 不受"清除缓存"与系统存储回收影响（cacheDir 可能被系统清理，
                // 导致下载完成到安装之间包丢失、安装器报"解析包失败"）
                val target = File(context.filesDir, "pika-update.apk")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                onProgress(done.toFloat() / total.toFloat(), done, total)
                            } else {
                                onProgress(-1f, done, -1L)
                            }
                        }
                    }
                }
                target
            }
        }

    /** 拉起系统安装器 */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 语义化版本比较：latest > current 才算有新版本 */
    fun isNewer(latest: String, current: String): Boolean {
        val parse: (String) -> List<Int> = { raw ->
            raw.trim().trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        }
        val a = parse(latest)
        val b = parse(current)
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}