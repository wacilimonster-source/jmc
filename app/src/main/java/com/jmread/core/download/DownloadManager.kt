package com.jmread.core.download

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import com.jmread.MainActivity
import com.jmread.core.MemoryBudget
import com.jmread.core.log.LogStore
import com.jmread.core.log.PerfTrace
import com.jmread.core.JmRepository
import com.jmread.core.netconfig.DomainPool
import com.jmread.core.scramble.ImageFormatDetector
import com.jmread.core.scramble.ImageScrambler
import com.jmread.core.scramble.ImageWorkScheduler
import com.jmread.core.scramble.readBytesCapped
import com.jmread.data.DownloadPrefs
import com.jmread.network.BcTls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
import java.util.Collections
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
    /** 导出元数据用；旧 manifest 无该字段时默认空串 */
    val author: String = "",
)

/** 运行时状态（含进度/速度/占用，不持久化） */
data class TaskRuntime(
    val task: DownloadTask,
    val status: DlStatus = DlStatus.PENDING,
    val downloadedPages: Int = 0,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val error: String = "",
    /** 瞬态等待原因（不写入 manifest，重启后按网络重算） */
    val waitingForWifi: Boolean = false,
    val waitingForNetwork: Boolean = false,
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

    /** 本轮已删除的 key：恢复清单时不得复活（P2-06） */
    private val deletedKeys = Collections.synchronizedSet(mutableSetOf<String>())

    private fun cancelRun(key: String) {
        runningJobs.remove(key)?.cancel()
    }

    private val _tasks = MutableStateFlow<List<TaskRuntime>>(emptyList())
    val tasks: StateFlow<List<TaskRuntime>> = _tasks

    // ---------- 已下载章节内存索引（PERF-07） ----------
    /**
     * 结构流：comicId -> 已完成章节号集合。
     *
     * 只在**结构**变化（入队/重试/删除/完成/恢复/打开时校验失效）时更新；
     * 字节进度与速度采样不触碰它。阅读器/详情目录此前订阅整个 tasks 流，
     * 每次进度/速度变化都对每章 `isDownloaded()` 遍历目录 —— 现在只做内存查询。
     */
    private val indexLock = Any()
    private val downloadedIndex = HashMap<String, MutableSet<Int>>()
    private val _downloadedStructure = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    val downloadedStructure: StateFlow<Map<String, Set<Int>>> = _downloadedStructure.asStateFlow()

    private fun indexAdd(comicId: String, order: Int) {
        synchronized(indexLock) {
            downloadedIndex.getOrPut(comicId) { HashSet() }.add(order)
            publishIndexLocked()
        }
    }

    private fun indexRemove(comicId: String, order: Int) {
        synchronized(indexLock) {
            val set = downloadedIndex[comicId] ?: return
            if (set.remove(order)) {
                if (set.isEmpty()) downloadedIndex.remove(comicId)
                publishIndexLocked()
            }
        }
    }

    /** 打开章节时的文件校验发现文件缺失：把该章从内存索引摘除（外部删页可检测） */
    fun invalidateDownloaded(comicId: String, order: Int) = indexRemove(comicId, order)

    private fun publishIndexLocked() {
        _downloadedStructure.value = downloadedIndex.entries.associate { it.key to it.value.toHashSet() }
    }

    // 派生状态显式化为 StateFlow：UI 直接订阅，避免依赖"某个恰好被读取的 tasks
    // collectAsState()"这类隐式耦合（一旦重构移入 if 分支，刷新会静默失效）。
    val totalBytesFlow: StateFlow<Long> = _tasks
        .map { list -> list.sumOf { it.totalBytes } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalSpeedFlow: StateFlow<Long> = _tasks
        .map { list -> list.sumOf { it.bytesPerSecond } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    const val CONCURRENCY = 2

    /** 单页最多尝试的 CDN 数：与图片逻辑请求的 4 次传输预算一致 */
    private const val MAX_DOWNLOAD_HOST_ATTEMPTS = 4

    private const val NOTIFY_CHANNEL_ID = "download"

    /** 本次下载批次：comicId -> 本次所选章节 order。完成/取消/删除会更新或移除。 */
    private val activeBatches = ConcurrentHashMap<String, MutableSet<Int>>()

    private enum class NetworkGate { ALLOWED, WAITING_WIFI, WAITING_NETWORK }

    private class NetworkGateException(val gate: NetworkGate) : Exception("network gate: $gate")

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { pump() }
        }

        override fun onLost(network: Network) {
            scope.launch { pump() }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            scope.launch { pump() }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        startPersistWorker()
        // 目录遍历可能较慢，放到后台协程，避免阻塞冷启动主线程
        scope.launch { PerfTrace.asyncSection("download.reconcile") { restoreTasks() } }
        startSpeedSampler()
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    /** 设置页切换「仅 Wi-Fi」后立即重新评估排队任务。 */
    fun refreshNetworkGate() {
        pump()
    }

    // ── 路径 ──────────────────────────────────────────────────────────────
    fun rootDir(): File = File(appContext.getExternalFilesDir(null), "downloads")

    fun chapterDir(comicId: String, order: Int): File =
        File(rootDir(), "$comicId/$order")

    fun pageFile(comicId: String, order: Int, index: Int): File =
        File(chapterDir(comicId, order), "page_${index + 1}.jpg")

    /** 章节是否已完整下载（离线可读）：即使 COMPLETED 也按磁盘文件校验，防止清单与文件不一致 */
    fun isDownloaded(comicId: String, order: Int): Boolean {
        val task = taskFor(comicId, order)
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
        author: String = "",
    ) {
        scope.launch {
            mutex.withLock {
                // 对存在任务重复入队 = 重新下载：先停掉旧执行者，再重置状态
                val enqueueKey = "$comicId#$order"
                deletedKeys.remove(enqueueKey)
                cancelRun(enqueueKey)
                val list = _tasks.value.toMutableList()
                val idx = list.indexOfFirst { it.key == "$comicId#$order" }
                val task = DownloadTask(comicId, comicTitle, coverUrl, order, epTitle, pageCount, author = author)
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
                // 重新入队 = 章节回到未完成：内存索引同步摘除
                indexRemove(comicId, order)
                _tasks.value = list
                addToBatch(comicId, listOf(order))
                requestPersist()
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
        author: String = "",
    ) {
        if (chapters.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val list = _tasks.value.toMutableList()
                val addedOrders = mutableSetOf<Int>()
                var changed = false
                // PERF-07：入队不做目录扫描。已下载判定只看内存索引/任务状态；
                // 被外部删页的章节会在打开时校验并摘出索引，之后仍可重新下载。
                val completed = synchronized(indexLock) {
                    downloadedIndex[comicId]?.toHashSet() ?: HashSet()
                }
                for ((order, epTitle) in chapters) {
                    val key = "$comicId#$order"
                    val existing = list.firstOrNull { it.key == key }
                    if (order in completed || existing?.isFinished == true) continue
                    addedOrders += order
                    deletedKeys.remove(key)
                    val idx = list.indexOfFirst { it.key == key }
                    if (idx >= 0) {
                        val old = list[idx]
                        // 旧执行者还在跑时先取消，避免同一 key 出现两个下载协程
                        if (old.status == DlStatus.DOWNLOADING) cancelRun(key)
                        list[idx] = old.copy(
                            task = old.task.copy(epTitle = epTitle),
                            status = DlStatus.PENDING,
                            error = "",
                        )
                        indexRemove(comicId, order)
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
                                    author = author,
                                ),
                            )
                        )
                        changed = true
                    }
                }
                if (changed) {
                    _tasks.value = list
                    addToBatch(comicId, addedOrders)
                    requestPersist()
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
                val target = _tasks.value.firstOrNull { it.key == key }
                _tasks.value = _tasks.value.map {
                    if (it.key == key && !it.isFinished) {
                        it.copy(
                            status = DlStatus.PENDING,
                            error = "",
                            downloadedPages = 0,
                            totalBytes = 0,
                            bytesPerSecond = 0,
                            waitingForWifi = false,
                            waitingForNetwork = false,
                        )
                    } else it
                }
                if (target != null) {
                    addToBatch(target.task.comicId, listOf(target.task.order))
                    // 重置回 PENDING：从内存索引摘除，完成后再加回
                    indexRemove(target.task.comicId, target.task.order)
                }
                requestPersist()
            }
            pump()
        }
    }

    fun remove(key: String, deleteFiles: Boolean = true) {
        scope.launch {
            mutex.withLock {
                // 先叫停进行中的下载，再移除任务与文件：否则旧协程会把刚删掉的目录写回来
                deletedKeys += key
                cancelRun(key)
                val target = _tasks.value.firstOrNull { it.key == key }
                _tasks.value = _tasks.value.filterNot { it.key == key }
                if (target != null) {
                    removeFromBatch(target.task.comicId, target.task.order)
                    indexRemove(target.task.comicId, target.task.order)
                }
                // 删除是关键边界：清单必须先落盘（tombstone 不复活），再删文件
                persistAndAwait()
                if (deleteFiles && target != null) {
                    chapterDir(target.task.comicId, target.task.order).deleteRecursively()
                }
                if (target != null) maybeNotifyBatch(target.task.comicId)
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
    /** 当前默认网络是否允许启动下载（严格仅 Wi-Fi 含计费热点；以太网等待）。 */
    private fun currentGate(): NetworkGate {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkGate.ALLOWED
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return NetworkGate.WAITING_NETWORK
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!hasInternet) return NetworkGate.WAITING_NETWORK
        if (DownloadPrefs.wifiOnly &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) {
            return NetworkGate.WAITING_WIFI
        }
        return NetworkGate.ALLOWED
    }

    /**
     * 把空闲并发槽补满。
     * 网络门禁不通过时只更新 PENDING 任务的等待原因，不启动、不标记失败。
     */
    private fun pump() {
        scope.launch {
            val gate = currentGate()
            val toStart = mutableListOf<String>()
            mutex.withLock {
                if (gate != NetworkGate.ALLOWED) {
                    _tasks.value = _tasks.value.map {
                        if (it.status == DlStatus.PENDING) {
                            it.copy(
                                waitingForWifi = gate == NetworkGate.WAITING_WIFI,
                                waitingForNetwork = gate == NetworkGate.WAITING_NETWORK,
                            )
                        } else {
                            it
                        }
                    }
                    return@withLock
                }
                _tasks.value = _tasks.value.map {
                    if (it.status == DlStatus.PENDING) {
                        it.copy(waitingForWifi = false, waitingForNetwork = false)
                    } else {
                        it
                    }
                }
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
                            if (it.key in keys) {
                                it.copy(
                                    status = DlStatus.DOWNLOADING,
                                    waitingForWifi = false,
                                    waitingForNetwork = false,
                                )
                            } else {
                                it
                            }
                        }
                        refreshSpeedActiveLocked()
                        requestPersist()
                        toStart += keys
                    }
                }
            }
            // 锁外启动，避免重新挑任务时挑到已在跑的任务
            toStart.forEach { runTask(it) }
        }
    }

    // ── 下载完成通知（P0-04） ──────────────────────────────────────────────
    private fun addToBatch(comicId: String, orders: Collection<Int>) {
        if (orders.isEmpty()) return
        val set = activeBatches.getOrPut(comicId) {
            Collections.synchronizedSet(mutableSetOf<Int>())
        }
        set.addAll(orders)
    }

    private fun removeFromBatch(comicId: String, order: Int) {
        val set = activeBatches[comicId] ?: return
        set.remove(order)
        if (set.isEmpty()) activeBatches.remove(comicId, set)
    }

    /** 本批所选章节全部完成才通知；失败/取消/删除都不算完成。恢复清单不建立批次，不补发历史通知。 */
    private fun maybeNotifyBatch(comicId: String) {
        val orders = activeBatches[comicId] ?: return
        if (orders.isEmpty()) {
            activeBatches.remove(comicId)
            return
        }
        val tasks = _tasks.value.filter { it.task.comicId == comicId }
        val allDone = orders.all { order ->
            tasks.any { it.task.order == order && it.isFinished }
        }
        if (allDone) {
            activeBatches.remove(comicId)
            scope.launch { notifyComplete(comicId, tasks.filter { it.task.order in orders }) }
        }
    }

    private suspend fun notifyComplete(comicId: String, tasks: List<TaskRuntime>) {
        if (!DownloadPrefs.notifyEnabled || tasks.isEmpty()) return
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        NOTIFY_CHANNEL_ID,
                        "下载",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val first = tasks.first().task
            val title = first.comicTitle.ifBlank { comicId }
            val text = if (tasks.size == 1) {
                val ep = first.epTitle.ifBlank { "第 ${first.order} 话" }
                "《$title》$ep 下载完成"
            } else {
                "《$title》本次所选章节已下载完成"
            }
            val route = "comic/$comicId"
            val intent = Intent(appContext, MainActivity::class.java).apply {
                putExtra("open_route", route)
                // 显式 Activity + 唯一 data URI：避免不同漫画的 PendingIntent extra 串书
                data = Uri.parse("jmread://comic/${Uri.encode(comicId)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val largeIcon = runCatching {
                val request = ImageRequest.Builder(appContext)
                    .data(first.coverUrl.takeIf { it.isNotBlank() })
                    .allowHardware(false)
                    .size(256)
                    .build()
                Coil.imageLoader(appContext).execute(request).drawable?.toBitmap()
            }.getOrNull()
            val builder = NotificationCompat.Builder(appContext, NOTIFY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
            if (largeIcon != null) builder.setLargeIcon(largeIcon)
            val tag = "dl_$comicId"
            manager.notify(tag, tag.hashCode(), builder.build())
        } catch (e: Exception) {
            LogStore.log("DownloadManager", "E", "notify complete failed: ${e.message}")
        }
    }

    /** 网络不满足：结束当前执行者，任务回到 PENDING 并保留已下载页；不记为失败。 */
    private suspend fun parkForNetwork(key: String, gate: NetworkGate) {
        mutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.key == key && it.status == DlStatus.DOWNLOADING) {
                    it.copy(
                        status = DlStatus.PENDING,
                        waitingForWifi = gate == NetworkGate.WAITING_WIFI,
                        waitingForNetwork = gate == NetworkGate.WAITING_NETWORK,
                        bytesPerSecond = 0,
                    )
                } else {
                    it
                }
            }
            refreshSpeedActiveLocked()
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
                val startGate = currentGate()
                if (startGate != NetworkGate.ALLOWED) {
                    parkForNetwork(key, startGate)
                    return@launch
                }
                try {
                    val pages = JmRepository.chapterPages(t.comicId, t.order)
                    // 空章节（付费/锁定/接口异常）不能当成 0 页下载完成
                    if (pages.isEmpty()) throw IOException("章节没有可下载的图片")
                    // 真实页数在运行时才可知（整本批量入队时为 0），拉取后回填
                    if (t.pageCount != pages.size) {
                        mutex.withLock {
                            _tasks.value = _tasks.value.map {
                                if (it.key == key) {
                                    it.copy(task = it.task.copy(pageCount = pages.size))
                                } else it
                            }
                            requestPersist()
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
                        // 每页请求前再检查一次：切换网络后当前文件可完成，但不再发起新页
                        val pageGate = currentGate()
                        if (pageGate != NetworkGate.ALLOWED) {
                            parkForNetwork(key, pageGate)
                            return@launch
                        }
                        val n = downloadFile(page.imageUrl, file, runTag)
                        bytes += n
                        updateProgress(key, i + 1, bytes)
                    }
                    mutex.withLock {
                        _tasks.value = _tasks.value.map {
                            if (it.key == key) {
                                it.copy(
                                    status = DlStatus.COMPLETED,
                                    error = "",
                                    waitingForWifi = false,
                                    waitingForNetwork = false,
                                )
                            } else {
                                it
                            }
                        }
                        // 结构变化：该章进入内存索引；完成是关键边界，落盘后再返回
                        indexAdd(t.comicId, t.order)
                        refreshSpeedActiveLocked()
                        persistAndAwait()
                    }
                    maybeNotifyBatch(t.comicId)
                } catch (e: NetworkGateException) {
                    parkForNetwork(key, e.gate)
                } catch (e: CancellationException) {
                    // 取消不是下载失败：retry/remove 已接管状态，旧执行者不得写回 FAILED
                    throw e
                } catch (e: Exception) {
                    val gateNow = currentGate()
                    if (gateNow != NetworkGate.ALLOWED) {
                        // 下载中途断网/切到移动网络：保留进度等待，不标记失败
                        parkForNetwork(key, gateNow)
                    } else {
                        mutex.withLock {
                            _tasks.value = _tasks.value.map {
                                if (it.key == key) {
                                    it.copy(
                                        status = DlStatus.FAILED,
                                        error = e.message ?: "下载失败",
                                        waitingForWifi = false,
                                        waitingForNetwork = false,
                                    )
                                } else {
                                    it
                                }
                            }
                            refreshSpeedActiveLocked()
                            requestPersist()
                        }
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
     *
     * 资源顺序（PERF-02）：网络传输只在 [ImageWorkScheduler] 的下载配额内进行，
     * 拿到字节后立即释放网络槽位，再进入像素处理预算，避免“拿着网络名额做计算”。
     * 单页最多 4 次实际传输，防止 7 个 CDN 候选把失败放大。
     */
    private suspend fun downloadFile(urlStr: String, dest: File, runTag: String): Long {
        val first = URL(urlStr)
        // 各 CDN 的路径完全一致（/media/photos/{id}/{file}），只换 host 即可
        val pathAndQuery = first.file
        var lastError: Exception? = null
        var attempts = 0
        for (host in DomainPool.imageHostOrder(first.host)) {
            if (attempts >= MAX_DOWNLOAD_HOST_ATTEMPTS) break
            attempts++
            val gate = currentGate()
            if (gate != NetworkGate.ALLOWED) throw NetworkGateException(gate)

            // 1) 网络阶段：失败才是 CDN 故障，换下一条线路
            val bytes = try {
                ImageWorkScheduler.withSlot(
                    ImageWorkScheduler.Priority.DOWNLOAD,
                    host,
                ) {
                    fetchBytes("https://$host$pathAndQuery")
                }
            } catch (e: NetworkGateException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                DomainPool.markImageBad(host)
                LogStore.log(
                    "jm-dl", "WARN",
                    "下载图片 CDN $host 失败，换下一条线路：${e.message?.take(70)}",
                )
                continue
            }

            // 2) 200 + 风控页/空响应：属于该 CDN 的异常，换线有意义
            if (!ImageFormatDetector.isImage(bytes)) {
                lastError = IOException("响应不是有效图片（${bytes.size} bytes）")
                DomainPool.markImageBad(host)
                LogStore.log(
                    "jm-dl", "WARN",
                    "下载图片 CDN $host 返回非图片（${bytes.size} bytes），换下一条线路",
                )
                continue
            }

            // 3) 处理/落盘失败不是 CDN 故障：换线只会把同一张图再下一遍
            try {
                val processed = ImageScrambler.processBytesIfNeeded(
                    urlStr,
                    bytes,
                    maxHeight = MemoryBudget.maxDecodeHeightPx(appContext),
                )
                writeProcessed(dest, runTag, processed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogStore.log(
                    "jm-dl", "E",
                    "下载图片处理/落盘失败（不换线）：${e.message?.take(70)}",
                )
                throw e
            }
            DomainPool.markImageGood(host)
            return dest.length()
        }
        throw lastError ?: IOException("下载失败（所有 CDN 均不可用）: $urlStr")
    }

    /** 纯网络阶段：只负责取字节，取消时断开连接。 */
    private suspend fun fetchBytes(urlStr: String): ByteArray {
        val url = URL(urlStr)
        val conn: HttpURLConnection = if (url.protocol == "https") {
            BcTls.openConnection(url)
        } else {
            url.openConnection() as HttpURLConnection
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "okhttp/4.12.0")
        // 取消绑定：任务被删除/重试接管时断开连接，阻塞读立刻失败，不再占带宽
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) runCatching { conn.disconnect() }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.use { input -> input.readBytesCapped() }
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }

    /** 原子落盘：先写 .part 再 rename，失败清理半成品。 */
    private fun writeProcessed(dest: File, runTag: String, bytes: ByteArray) {
        val tmp = File(dest.parentFile, "${dest.name}.$runTag.part")
        try {
            tmp.outputStream().use { output -> output.write(bytes) }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 失败即清理半成品，避免 .part 残留占用空间且磁盘占用不可见
            tmp.delete()
            throw e
        }
    }

    // ── 持久化（单写者，PERF-07） ─────────────────────────────────────────
    private fun manifestFile(): File = File(rootDir(), "manifest.json")

    /**
     * 旧实现在持有 mutex 的调用线程上同步写盘：每次入队/完成/失败都串行化
     * 整个任务列表 JSON，进度高峰期会拖慢调度。现在改为单写者协程：
     *  - 非关键写（入队/页数回填/失败）进通道，突发请求合并为一次写盘；
     *  - 关键写（完成/删除）经 [persistAndAwait] 拿到落盘边界后才继续；
     *  - 写盘始终读最新快照，通道里排队多份也只写最终状态。
     * `.part` 隔离、恢复合并与 tombstone 语义保持不变。
     */
    private class PersistRequest(val gate: CompletableDeferred<Unit>? = null)

    private val persistChannel = Channel<PersistRequest>(Channel.UNLIMITED)
    @Volatile private var persistWorkerStarted = false

    private fun startPersistWorker() {
        if (persistWorkerStarted) return
        persistWorkerStarted = true
        scope.launch {
            for (request in persistChannel) {
                // 排空积压：中间态合并成一次写盘，但每个 gate 都要被完成
                val gates = mutableListOf<CompletableDeferred<Unit>?>()
                gates += request.gate
                while (true) {
                    val next = persistChannel.tryReceive().getOrNull() ?: break
                    gates += next.gate
                }
                writeManifest()
                gates.forEach { it?.complete(Unit) }
            }
        }
    }

    /** 非关键持久化请求：多次合并为一次写盘。 */
    private fun requestPersist() {
        persistChannel.trySend(PersistRequest())
    }

    /** 关键持久化边界：等待清单实际写盘完成（不持锁 —— writeManifest 只读快照）。 */
    private suspend fun persistAndAwait() {
        val gate = CompletableDeferred<Unit>()
        persistChannel.send(PersistRequest(gate))
        gate.await()
    }

    private fun writeManifest() {
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

    private suspend fun restoreTasks() {
        val f = manifestFile()
        if (!f.exists()) return
        try {
            val saved: List<DownloadTask> =
                json.decodeFromString(ListSerializer(DownloadTask.serializer()), f.readText())
            val restored = saved.map { task ->
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
            mutex.withLock {
                val current = _tasks.value
                val merged = mergeRestoredTasks(current, restored, deletedKeys.toSet())
                _tasks.value = merged
                // PERF-07：恢复本来就逐章扫描过目录，这里顺手一次性建立内存索引，
                // 之后阅读器/详情目录的"已下载"查询全部走内存
                synchronized(indexLock) {
                    downloadedIndex.clear()
                    for (task in merged) {
                        if (task.status == DlStatus.COMPLETED) {
                            downloadedIndex.getOrPut(task.task.comicId) { HashSet() }
                                .add(task.task.order)
                        }
                    }
                    publishIndexLocked()
                }
                refreshSpeedActiveLocked()
                // 合并结果必须立刻落盘：恢复期间发生的 enqueue 可能已用旧列表覆盖过清单
                persistAndAwait()
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
     * 速度采样活跃信号（PERF-07）：有 DOWNLOADING 任务才唤醒采样循环。
     * 旧实现常驻每 5 秒空转轮询任务列表；现在空闲时挂起、零唤醒，
     * 且只对速度值真正变化的任务重建列表。
     */
    private val speedActive = MutableStateFlow(false)

    /** 必须在持有 [mutex] 时调用（任务状态刚发生变化）。 */
    private fun refreshSpeedActiveLocked() {
        speedActive.value = _tasks.value.any { it.status == DlStatus.DOWNLOADING }
    }

    private fun startSpeedSampler() {
        scope.launch {
            while (true) {
                speedActive.first { it }
                var prevBytes = 0L
                var prevAt = 0L
                while (speedActive.value) {
                    val now = System.currentTimeMillis()
                    val bytes = _tasks.value.sumOf { it.totalBytes }
                    if (prevAt != 0L && now - prevAt >= 1500) {
                        val speed = ((bytes - prevBytes) * 1000L / (now - prevAt)).coerceAtLeast(0L)
                        mutex.withLock {
                            _tasks.value = _tasks.value.map {
                                when {
                                    it.status == DlStatus.DOWNLOADING && it.bytesPerSecond != speed ->
                                        it.copy(bytesPerSecond = speed)
                                    it.status != DlStatus.DOWNLOADING && it.bytesPerSecond != 0L ->
                                        it.copy(bytesPerSecond = 0)
                                    else -> it
                                }
                            }
                            // 采样期间任务可能全部结束/被删：及时收回活跃信号
                            refreshSpeedActiveLocked()
                        }
                        prevBytes = bytes
                        prevAt = now
                    } else if (prevAt == 0L) {
                        prevBytes = bytes
                        prevAt = now
                    }
                    delay(1_000)
                }
                // 从活跃转空闲：清零一次速度
                mutex.withLock {
                    _tasks.value = _tasks.value.map {
                        if (it.bytesPerSecond != 0L) it.copy(bytesPerSecond = 0) else it
                    }
                }
            }
        }
    }
}

/**
 * 恢复清单合并（纯函数，P2-06 单测用）：current 优先；本轮已删除 key 不复活。
 */
internal fun mergeRestoredTasks(
    current: List<TaskRuntime>,
    restored: List<TaskRuntime>,
    deletedKeys: Set<String>,
): List<TaskRuntime> {
    val currentKeys = current.map { it.key }.toSet()
    return current + restored.filter { it.key !in currentKeys && it.key !in deletedKeys }
}

/** 列表序列化辅助 */
private fun <T> ListSerializer(serializer: kotlinx.serialization.KSerializer<T>) =
    kotlinx.serialization.builtins.ListSerializer(serializer)
