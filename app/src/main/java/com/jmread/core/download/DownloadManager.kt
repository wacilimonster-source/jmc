package com.jmread.core.download

import android.content.Context
import com.jmread.core.log.LogStore
import com.jmread.core.JmRepository
import com.jmread.core.netconfig.DomainPool
import com.jmread.core.scramble.ImageScrambler
import com.jmread.network.BcTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** 下载任务状态 */
@Serializable
enum class DlStatus { PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELED }

/** 持久化任务描述 */
@Serializable
data class DownloadTask(
    val comicId: String,
    val comicTitle: String,
    val coverUrl: String,
    val order: Int,
    val epTitle: String,
    val pageCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 运行时状态（含进度/速度/占用，不持久化） */
data class TaskRuntime(
    val task: DownloadTask,
    val status: DlStatus = DlStatus.PENDING,
    val downloadedPages: Int = 0,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val error: String = "",
) {
    val key: String get() = "${task.comicId}#${task.order}"
    val progress: Int get() = if (task.pageCount > 0) (downloadedPages * 100 / task.pageCount) else 0
    val isFinished: Boolean get() = status == DlStatus.COMPLETED
}

/**
 * 下载管理器（单例）：
 * - 章节图片下载到 app 外部文件目录 downloads/{comicId}/{order}/page_*.jpg
 * - 任务列表持久化（manifest.json），重启恢复
 * - 进度 / 速度 / 存储占用实时上报；失败可重试；支持并发（默认 2 路）
 */
object DownloadManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 运行中任务 → 执行协程。任务的所有权登记：
     * 入队/重试/删除先取消登记的旧协程，保证同一任务任意时刻只有一个执行者，
     * 杜绝两个协程并发写同一章节目录（交错写入会把成品图片写坏）。
     */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    private fun cancelRun(key: String) {
        runningJobs.remove(key)?.cancel()
    }

    private val _tasks = MutableStateFlow<List<TaskRuntime>>(emptyList())
    val tasks: StateFlow<List<TaskRuntime>> = _tasks

    // 派生状态显式化为 StateFlow：UI 直接订阅，避免依赖"某个恰好被读取的 tasks
    // collectAsState()"这类隐式耦合（一旦重构移入 if 分支，刷新会静默失效）。
    val totalBytesFlow: StateFlow<Long> = _tasks
        .map { list -> list.sumOf { it.totalBytes } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalSpeedFlow: StateFlow<Long> = _tasks
        .map { list -> list.sumOf { it.bytesPerSecond } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    const val CONCURRENCY = 2

    fun init(context: Context) {
        appContext = context.applicationContext
        // 目录遍历可能较慢，放到后台协程，避免阻塞冷启动主线程
        scope.launch { restoreTasks() }
        startSpeedSampler()
    }

    // ── 路径 ──────────────────────────────────────────────────────────────
    fun rootDir(): File = File(appContext.getExternalFilesDir(null), "downloads")

    fun chapterDir(comicId: String, order: Int): File =
        File(rootDir(), "$comicId/$order")

    fun pageFile(comicId: String, order: Int, index: Int): File =
        File(chapterDir(comicId, order), "page_${index + 1}.jpg")

    /** 章节是否已完整下载（离线可读）：按任务记录的页数校验，避免半下载误判 */
    fun isDownloaded(comicId: String, order: Int): Boolean {
        val task = taskFor(comicId, order)
        if (task?.status == DlStatus.COMPLETED) return true
        val dir = chapterDir(comicId, order)
        // .part 是写入中的半成品，进程被杀会残留，不能计入有效页数
        val files = dir.listFiles()?.filter {
            it.name.startsWith("page_") && !it.name.endsWith(".part") && it.length() > 0
        } ?: return false
        val expected = task?.task?.pageCount ?: 0
        return if (expected > 0) files.size >= expected else files.isNotEmpty()
    }

    /** 某漫画的全部任务（按章节号排序） */
    fun comicTasks(comicId: String): List<TaskRuntime> =
        _tasks.value.filter { it.task.comicId == comicId }.sortedBy { it.task.order }

    /** 某漫画的下载进度摘要（已完成章数 / 总章数） */
    fun comicProgress(comicId: String): Pair<Int, Int> {
        val list = comicTasks(comicId)
        return list.count { it.isFinished } to list.size
    }

    // ── 任务管理 ──────────────────────────────────────────────────────────
    fun enqueue(
        comicId: String,
        comicTitle: String,
        coverUrl: String,
        order: Int,
        epTitle: String,
        pageCount: Int,
    ) {
        scope.launch {
            mutex.withLock {
                // 对存在任务重复入队 = 重新下载：先停掉旧执行者，再重置状态
                cancelRun("$comicId#$order")
                val list = _tasks.value.toMutableList()
                val idx = list.indexOfFirst { it.key == "$comicId#$order" }
                val task = DownloadTask(comicId, comicTitle, coverUrl, order, epTitle, pageCount)
                if (idx >= 0) {
                    val old = list[idx]
                    list[idx] = old.copy(
                        task = task,
                        status = DlStatus.PENDING,
                        downloadedPages = 0,
                        totalBytes = 0,
                        error = "",
                    )
                } else {
                    list.add(TaskRuntime(task))
                }
                _tasks.value = list
                persist()
            }
            pump()
        }
    }

    /** 批量入队整本漫画：一次加锁写入全部章节任务，跳过已下载章节 */
    fun enqueueAll(
        comicId: String,
        comicTitle: String,
        coverUrl: String,
        chapters: List<Pair<Int, String>>,
    ) {
        if (chapters.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val list = _tasks.value.toMutableList()
                var changed = false
                for ((order, epTitle) in chapters) {
                    if (isDownloaded(comicId, order)) continue
                    val key = "$comicId#$order"
                    val idx = list.indexOfFirst { it.key == key }
                    if (idx >= 0) {
                        val old = list[idx]
                        if (old.isFinished) continue
                        list[idx] = old.copy(
                            task = old.task.copy(epTitle = epTitle),
                            status = DlStatus.PENDING,
                            error = "",
                        )
                        changed = true
                    } else {
                        list.add(
                            TaskRuntime(
                                DownloadTask(
                                    comicId = comicId,
                                    comicTitle = comicTitle,
                                    coverUrl = coverUrl,
                                    order = order,
                                    epTitle = epTitle,
                                    pageCount = 0,
                                ),
                            )
                        )
                        changed = true
                    }
                }
                if (changed) {
                    _tasks.value = list
                    persist()
                }
            }
            pump()
        }
    }

    fun retry(key: String) {
        scope.launch {
            mutex.withLock {
                // 可能重试一个仍在下载中的任务：先停掉旧执行者，避免双协程并发
                cancelRun(key)
                _tasks.value = _tasks.value.map {
                    if (it.key == key && !it.isFinished) {
                        it.copy(
                            status = DlStatus.PENDING,
                            error = "",
                            downloadedPages = 0,
                            totalBytes = 0,
                            bytesPerSecond = 0,
                        )
                    } else it
                }
                persist()
            }
            pump()
        }
    }

    fun remove(key: String, deleteFiles: Boolean = true) {
        scope.launch {
            mutex.withLock {
                // 先叫停进行中的下载，再移除任务与文件：否则旧协程会把刚删掉的目录写回来
                cancelRun(key)
                val target = _tasks.value.firstOrNull { it.key == key }
                _tasks.value = _tasks.value.filterNot { it.key == key }
                persist()
                if (deleteFiles && target != null) {
                    chapterDir(target.task.comicId, target.task.order).deleteRecursively()
                }
            }
        }
    }

    fun taskFor(comicId: String, order: Int): TaskRuntime? =
        _tasks.value.firstOrNull { it.key == "$comicId#$order" }

    // ── 调度 ──────────────────────────────────────────────────────────────

    /**
     * 把空闲并发槽补满：单次 pump 可能启动多个任务（此前每次只启动 1 个，
     * 导致 CONCURRENCY 名存实亡、整本下载实为串行）。
     */
    private fun pump() {
        scope.launch {
            val toStart = mutableListOf<String>()
            mutex.withLock {
                val running = _tasks.value.count { it.status == DlStatus.DOWNLOADING }
                val slots = (CONCURRENCY - running).coerceAtLeast(0)
                if (slots > 0) {
                    val keys = _tasks.value
                        .filter { it.status == DlStatus.PENDING }
                        .take(slots)
                        .map { it.key }
                        .toSet()
                    if (keys.isNotEmpty()) {
                        _tasks.value = _tasks.value.map {
                            if (it.key in keys) it.copy(status = DlStatus.DOWNLOADING) else it
                        }
                        persist()
                        toStart += keys
                    }
                }
            }
            // 锁外启动，避免重新挑任务时挑到已在跑的任务
            toStart.forEach { runTask(it) }
        }
    }

    private fun runTask(key: String) {
        scope.launch {
            // 登记为该任务唯一的执行者；若旧执行者仍在（如状态被外部重置），立即取消
            val self = coroutineContext[Job]
            if (self != null) {
                runningJobs.put(key, self)?.let { old -> if (old !== self) old.cancel() }
            }
            try {
                val task = _tasks.value.firstOrNull { it.key == key } ?: return@launch
                val t = task.task
                // 任务在排队与启动之间被删除/重置时不再执行
                if (task.status != DlStatus.DOWNLOADING) return@launch
                try {
                    val pages = JmRepository.chapterPages(t.comicId, t.order)
                    // 真实页数在运行时才可知（整本批量入队时为 0），拉取后回填
                    if (t.pageCount != pages.size) {
                        mutex.withLock {
                            _tasks.value = _tasks.value.map {
                                if (it.key == key) {
                                    it.copy(task = it.task.copy(pageCount = pages.size))
                                } else it
                            }
                            persist()
                        }
                    }
                    val dir = chapterDir(t.comicId, t.order)
                    dir.mkdirs()
                    // 本次执行的临时文件标签：即使新旧执行者短暂重叠，也各写各的 .part，
                    // 不会交错写坏同一个文件（完成后各自 rename，成品始终是完整图片）
                    val runTag = java.util.UUID.randomUUID().toString()
                    var bytes = 0L
                    for ((i, page) in pages.withIndex()) {
                        // 所有权检查：任务被删除 / 重新入队 / 重试接管（状态不再归本执行者）时立即停止
                        val cur = _tasks.value.firstOrNull { it.key == key }
                        if (cur == null || cur.status != DlStatus.DOWNLOADING) return@launch
                        // 已下载的页跳过
                        val file = pageFile(t.comicId, t.order, i)
                        if (file.exists() && file.length() > 0) {
                            bytes += file.length()
                            updateProgress(key, i + 1, bytes)
                            continue
                        }
                        val n = downloadFile(page.imageUrl, file, runTag)
                        bytes += n
                        updateProgress(key, i + 1, bytes)
                    }
                    mutex.withLock {
                        _tasks.value = _tasks.value.map {
                            if (it.key == key) it.copy(status = DlStatus.COMPLETED, error = "") else it
                        }
                        persist()
                    }
                } catch (e: Exception) {
                    mutex.withLock {
                        _tasks.value = _tasks.value.map {
                            if (it.key == key) it.copy(status = DlStatus.FAILED, error = e.message ?: "下载失败") else it
                        }
                        persist()
                    }
                }
            } finally {
                // 仅清除自己的登记（新执行者可能已接管同一 key）
                self?.let { runningJobs.remove(key, it) }
                pump()
            }
        }
    }

    private suspend fun updateProgress(key: String, downloadedPages: Int, totalBytes: Long) {
        mutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.key == key) it.copy(downloadedPages = downloadedPages, totalBytes = totalBytes) else it
            }
        }
    }

    /**
     * 下载单个文件（BouncyCastle TLS，绕 Cloudflare）。runTag 用于隔离并发执行的半成品文件。
     * 落盘前做乱序判定与还原（离线阅读必须与在线一致），扩展名按魔数定（实际为 webp）。
     *
     * 图片 CDN 失败时换下一个 host 重试：CDN 域名与 API 是两套，
     * 且各 CDN 会按区域被 DNS 屏蔽（实测 *.jmapiproxy3.cc 被解析到 127.0.0.1）。
     * 只试一个 host 的话，整本书的下载会卡在同一个不可用 CDN 上。
     */
    private fun downloadFile(urlStr: String, dest: File, runTag: String): Long {
        val first = URL(urlStr)
        // 各 CDN 的路径完全一致（/media/photos/{id}/{file}），只换 host 即可
        val pathAndQuery = first.file
        var lastError: Exception? = null
        for (host in DomainPool.imageHostOrder(first.host)) {
            try {
                val size = fetchToFile("https://$host$pathAndQuery", dest, runTag)
                DomainPool.markImageGood(host)
                return size
            } catch (e: Exception) {
                lastError = e
                DomainPool.markImageBad(host)
                LogStore.log("jm-dl", "WARN", "下载图片 CDN $host 失败，换下一条线路：${e.message?.take(70)}")
            }
        }
        throw lastError ?: IOException("下载失败（所有 CDN 均不可用）: $urlStr")
    }

    private fun fetchToFile(urlStr: String, dest: File, runTag: String): Long {
        val url = URL(urlStr)
        val conn: HttpURLConnection = if (url.protocol == "https") {
            BcTls.openConnection(url)
        } else {
            url.openConnection() as HttpURLConnection
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "okhttp/4.12.0")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val tmp = File(dest.parentFile, "${dest.name}.$runTag.part")
            try {
                val bytes = conn.inputStream.use { input -> input.readBytes() }
                // 章节图判定乱序并还原后落盘（Android 渲染按内容解码，文件名扩展名不影响）；未命中原样落盘
                val processed = ImageScrambler.processBytesIfNeeded(urlStr, bytes)
                tmp.outputStream().use { output -> output.write(processed) }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // 失败即清理半成品，避免 .part 残留占用空间且磁盘占用不可见
                tmp.delete()
                throw e
            }
            return dest.length()
        } finally {
            conn.disconnect()
        }
    }

    // ── 持久化 ────────────────────────────────────────────────────────────
    private fun manifestFile(): File = File(rootDir(), "manifest.json")

    private fun persist() {
        try {
            manifestFile().parentFile?.mkdirs()
            val tasks = _tasks.value
                .filter { it.status != DlStatus.CANCELED }
                .map { it.task }
            // 先写临时文件再原子替换：直接覆写时进程被杀会把清单截断，
            // 下次恢复为空列表后任意一次 persist 又用空内容覆盖，全部任务记录丢失
            val tmp = File(manifestFile().parentFile, "manifest.json.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(DownloadTask.serializer()), tasks))
            if (!tmp.renameTo(manifestFile())) {
                // 个别文件系统不允许覆盖式 rename：先删旧文件再试一次
                manifestFile().delete()
                if (!tmp.renameTo(manifestFile())) {
                    throw IOException("manifest rename failed")
                }
            }
        } catch (e: Exception) {
            // 静默吞掉会导致"内存正常、重启全丢"且无从排查，至少落日志
            LogStore.log("DownloadManager", "E", "persist failed: ${e.message}")
        }
    }

    private fun restoreTasks() {
        val f = manifestFile()
        if (!f.exists()) return
        try {
            val saved: List<DownloadTask> =
                json.decodeFromString(ListSerializer(DownloadTask.serializer()), f.readText())
            _tasks.value = saved.map { task ->
                val dir = chapterDir(task.comicId, task.order)
                // 清理上次运行残留的 .part 半成品：不计入页数，也不再占空间
                dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                val pages = dir.listFiles()?.count {
                    it.name.startsWith("page_") && !it.name.endsWith(".part")
                } ?: 0
                val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                // pageCount==0（真实页数未拉到过）时不能判为 COMPLETED，否则磁盘上一页都没有
                val finished = task.pageCount > 0 && pages >= task.pageCount
                TaskRuntime(
                    task = task,
                    status = if (finished) DlStatus.COMPLETED else DlStatus.FAILED,
                    downloadedPages = pages.coerceAtMost(task.pageCount),
                    totalBytes = bytes,
                    error = if (finished) "" else "上次未完成，可重试",
                )
            }
        } catch (e: Exception) {
            // 恢复失败必须可见：静默吞掉会让内存列表为空，之后任意一次 persist
            // 用空列表覆盖清单，全部任务记录不可逆丢失。损坏文件保留一份供排查。
            LogStore.log("DownloadManager", "E", "restore manifest failed: ${e.message}")
            runCatching {
                val bad = File(rootDir(), "manifest.json.bad")
                bad.delete()
                f.renameTo(bad)
            }
        }
    }

    // ── 速度采样 ──────────────────────────────────────────────────────────

    /**
     * 速度采样：无活跃任务时进入低频空转（5 秒一次且不做任何状态写入），
     * 避免应用全程每秒唤醒、争抢 mutex 并重建任务列表触发无谓的 StateFlow 更新。
     */
    private fun startSpeedSampler() {
        scope.launch {
            var prevBytes = 0L
            var prevAt = 0L
            while (true) {
                val snapshot = _tasks.value
                if (snapshot.none { it.status == DlStatus.DOWNLOADING }) {
                    // 从活跃转为空闲：清零一次速度，随后低频休眠
                    if (prevAt != 0L) {
                        mutex.withLock {
                            _tasks.value = _tasks.value.map {
                                if (it.bytesPerSecond != 0L) it.copy(bytesPerSecond = 0) else it
                            }
                        }
                        prevAt = 0L
                        prevBytes = 0L
                    }
                    delay(5_000)
                    continue
                }
                val now = System.currentTimeMillis()
                val bytes = snapshot.sumOf { it.totalBytes }
                if (prevAt != 0L && now - prevAt >= 1500) {
                    val speed = ((bytes - prevBytes) * 1000L / (now - prevAt)).coerceAtLeast(0L)
                    mutex.withLock {
                        _tasks.value = _tasks.value.map {
                            if (it.status == DlStatus.DOWNLOADING) it.copy(bytesPerSecond = speed) else it.copy(bytesPerSecond = 0)
                        }
                    }
                    prevBytes = bytes
                    prevAt = now
                } else if (prevAt == 0L) {
                    prevBytes = bytes
                    prevAt = now
                }
                delay(1000)
            }
        }
    }
}

/** 列表序列化辅助 */
private fun <T> ListSerializer(serializer: kotlinx.serialization.KSerializer<T>) =
    kotlinx.serialization.builtins.ListSerializer(serializer)
