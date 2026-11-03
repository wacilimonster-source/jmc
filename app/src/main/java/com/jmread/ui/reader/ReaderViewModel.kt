package com.jmread.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.request.ImageRequest
import coil.size.Precision
import com.jmread.core.MemoryBudget
import com.jmread.core.log.LogStore
import com.jmread.core.log.PerfTrace
import com.jmread.core.model.ComicChapter
import com.jmread.core.model.ComicPage
import com.jmread.core.JmRepository
import com.jmread.core.runCatchingCancellable
import com.jmread.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读器 VM：加载某章全部图片页（当前源）+ 章节列表（切章用）+ 本地进度。
 *
 * 进度模型（修订版 P0-01/P1-08）：
 * - 旧键 progress_{ref} 继续维护，表示“作品最近读到哪”；
 * - 新增 chapter_progress_{ref} 按稳定 chapterId 记录每章页码，切回旧章可续读；
 * - 写盘携带读取时的 revision，清除阅读记录会递增 revision，迟到写入直接丢弃。
 */
class ReaderViewModel : ViewModel() {

    private val _pages = MutableStateFlow<List<ComicPage>>(emptyList())
    val pages: StateFlow<List<ComicPage>> = _pages

    private val _chapters = MutableStateFlow<List<ComicChapter>>(emptyList())
    val chapters: StateFlow<List<ComicChapter>> = _chapters

    /** 章节列表是否成功从服务端解析（离线下载兜底/失败都不算） */
    private val _chaptersResolved = MutableStateFlow(false)
    val chaptersResolved: StateFlow<Boolean> = _chaptersResolved

    private val _epTitle = MutableStateFlow("")
    val epTitle: StateFlow<String> = _epTitle

    /** 作品标题（阅读历史用，与章节标题区分） */
    private val _comicTitle = MutableStateFlow("")
    val comicTitle: StateFlow<String> = _comicTitle

    /** 作品作者（阅读历史用） */
    private val _comicAuthor = MutableStateFlow("")
    val comicAuthor: StateFlow<String> = _comicAuthor

    private val _coverUrl = MutableStateFlow("")
    val coverUrl: StateFlow<String> = _coverUrl

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    /** 已下载章节号（IO 预算，随下载任务变化刷新） */
    private val _downloadedOrders = MutableStateFlow<Set<Int>>(emptySet())
    val downloadedOrders: StateFlow<Set<Int>> = _downloadedOrders

    /** 已读章节身份集合（稳定 chapterId + order:N 回退） */
    private val _readChapterKeys = MutableStateFlow<Set<String>>(emptySet())
    val readChapterKeys: StateFlow<Set<String>> = _readChapterKeys

    var comicId: String = ""
        private set

    /** 作品标识：进度、已读、最近阅读、切片缓存都按它记账（单源 == comicId） */
    private var ref: String = ""

    var currentOrder: Int = 1
        private set

    /** 当前章节号的 StateFlow：UI 的"上一话/下一话"必须按它计算（导航路由参数在切章后不会更新） */
    private val _currentOrderFlow = MutableStateFlow(1)
    val currentOrderFlow: StateFlow<Int> = _currentOrderFlow

    private var loadedKey: String = ""

    private var loadJob: Job? = null
    private var downloadedJob: Job? = null

    /** 加载代际：切章后旧协程即使网络刚刚返回，也不得发布页面/恢复页/目录 */
    private var loadGeneration = 0

    /** 当前 pages 是否来自完整来源（完整离线章节或在线整章）；部分本地页不得据此判读完 */
    @Volatile private var pagesComplete = false

    /**
     * 预取窗口（PERF-06）：pageIndex -> Disposable，差量维护。
     *
     * 旧实现每次页码变化先 dispose 全部旧窗口，再重新入队前后最多 5 页——
     * 窗口交集也被取消重建，快速翻页时反复打断在途传输。
     * 现在：
     *  - 只 dispose 离开窗口的项，保留交集；
     *  - 窗口 = 后退 1 页 + 前进最多 2 页（方向性：前进为主）；
     *  - 快速 fling（250ms 内连续翻页）暂停新增预取，停止后由下一次调用重建；
     *  - 章节身份 / 滚动模式变化 / 内存压力 → 整窗失效（解码尺寸与展示保持一致）；
     *  - 预取请求带 `#prefetch` 标记走调度器低优先级，缓存身份与展示请求完全一致。
     */
    private val preloadWindow = LinkedHashMap<Int, coil.request.Disposable>()
    private var preloadChapterKey = ""
    private var preloadScrollMode: Boolean? = null
    private var lastPreloadCallAt = 0L

    private fun disposePreloadWindow() {
        preloadWindow.values.forEach { runCatching { it.dispose() } }
        preloadWindow.clear()
    }

    /** 本作品加载时的修订号；写盘携带它，清除阅读记录后旧写入失效 */
    @Volatile private var revisionAtLoad: Long = 0L

    /** 本章已打勾的 order（防止末页停留时反复写盘） */
    private var markedReadOrder: Int = 0

    /** 章节未就绪时用 order 回退打勾，目录回来后补记稳定 ID */
    private val pendingReadOrders = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    init {
        // 其他入口（书架清除/撤销/详情页）改动已读集合后，抽屉与已读勾即时刷新
        viewModelScope.launch(Dispatchers.IO) {
            ReaderPrefs.current().chapterReadVersion.collect {
                if (ref.isNotBlank()) refreshReadKeysInternal()
            }
        }
    }

    fun load(context: Context, ref: String, order: Int) {
        if (loadedKey == "$ref:$order" && _pages.value.isNotEmpty()) {
            _loading.value = false
            return
        }
        val previousRef = this.ref
        loadJob?.cancel()
        val gen = ++loadGeneration
        loadedKey = "$ref:$order"
        this.ref = ref
        this.comicId = ref
        this.currentOrder = order
        _currentOrderFlow.value = order
        markedReadOrder = 0
        if (previousRef != ref) {
            // 换作品：上一本的章节/已读/已下载不能带进新作品
            _chapters.value = emptyList()
            _chaptersResolved.value = false
            _readChapterKeys.value = emptySet()
            _downloadedOrders.value = emptySet()
            pendingReadOrders.clear()
        }
        // 切章/重试时先清空上一章页面：否则加载期间会顶着新章标题展示旧章内容，
        // 失败时也容易把旧页码写成新章进度。
        _pages.value = emptyList()
        // 同步置 true：清空 pages 与协程启动之间不能闪一帧“加载失败”
        _loading.value = true
        loadJob = viewModelScope.launch {
            // PERF-00：点击进入 → 本章 pages 可用的异步阶段
            val pagesTrace = PerfTrace.beginAsync("reader.pagesReady")
            // 复位上一章未消费的恢复页：加载期间重组只会读到 -1，
            // 恢复 effect 不可能拿着上一章的 pages 抢跑消费
            _pendingRestorePage.value = -1
            try {
                withContext(Dispatchers.IO) {
                    val revisionStart = runCatching {
                        ReaderPrefs.current().currentRevision(ref)
                    }.getOrDefault(0L)
                    revisionAtLoad = revisionStart

                    // 恢复本章进度：稳定 chapterId 优先，旧作品进度只在对应章节迁移一次
                    val chapterId = chapterIdFor(order)
                    val restore = runCatchingCancellable {
                        ReaderPrefs.current().chapterProgressAsync(ref, chapterId, order) ?: 0
                    }.getOrDefault(0)
                    val restoreSafe = if (runCatching {
                            ReaderPrefs.current().currentRevision(ref)
                        }.getOrDefault(-1L) == revisionStart
                    ) {
                        restore
                    } else {
                        0
                    }

                    // 本地页：排除写入中的 .part；只有「完整下载」才优先纯本地。
                    // 不完整时仍尝试网络；网络失败再退回本地已下载部分（能看多少看多少）。
                    // 关键：后续章节列表请求失败绝不能清空这里已读出的页面（离线阅读 P0）。
                    val localFiles = com.jmread.core.download.DownloadManager
                        .chapterDir(comicId, order)
                        .listFiles()
                        ?.filter {
                            it.name.startsWith("page_") &&
                                !it.name.endsWith(".part") &&
                                it.length() > 0
                        }
                        ?.sortedBy { it.name.replace(Regex("\\D"), "").toIntOrNull() ?: 0 }
                        .orEmpty()
                    val localPages = localFiles.mapIndexed { i, f ->
                        com.jmread.core.model.ComicPage(i, f.toURI().toString())
                    }
                    val localComplete = localPages.isNotEmpty() &&
                        com.jmread.core.download.DownloadManager.isDownloaded(comicId, order)
                    if (localPages.isNotEmpty() && !localComplete) {
                        // 打开时文件校验失败（外部删页/半章）：从内存索引摘除，
                        // 结构流不再误报该章已下载（PERF-07 的完整性兜底）
                        com.jmread.core.download.DownloadManager.invalidateDownloaded(comicId, order)
                    }
                    if (gen != loadGeneration) return@withContext
                    if (localComplete) {
                        _pages.value = localPages
                        pagesComplete = true
                    } else {
                        val remotePages = runCatchingCancellable {
                            JmRepository.chapterPages(comicId, order)
                        }
                        if (gen != loadGeneration) return@withContext
                        val online = remotePages.getOrNull()?.takeIf { it.isNotEmpty() }
                        _pages.value = online ?: localPages
                        pagesComplete = online != null
                        if (remotePages.isFailure && localPages.isNotEmpty()) {
                            LogStore.log(
                                "reader", "W",
                                "章节在线加载失败，改用本地不完整页面 comic=$comicId order=$order " +
                                    "(${remotePages.exceptionOrNull()?.message?.take(60)})",
                            )
                        }
                    }
                    if (gen != loadGeneration) return@withContext
                    // pages 就绪后再发布恢复页：effect 由状态变化确定性触发，
                    // 触发时 UI 收集到的 pages 必已是本章
                    _pendingRestorePage.value = restoreSafe
                    PerfTrace.endAsync(pagesTrace)

                    // 章节列表独立失败：离线/详情接口故障不应连累已经拿到的页面。
                    val remoteChapters = runCatchingCancellable { JmRepository.chapters(comicId) }
                    if (gen != loadGeneration) return@withContext
                    if (remoteChapters.isSuccess) {
                        val list = remoteChapters.getOrThrow()
                        _chapters.value = list
                        _chaptersResolved.value = true
                        // 目录有稳定 ID 后，先把离线期间按 order 记下的勾迁移到稳定 ID，
                        // 再清掉剩余歧义标记（此前直接清空会永久丢失离线已读状态）
                        if (list.isNotEmpty() && list.all { it.id.isNotBlank() }) {
                            migrateOrderFallbackReads(comicId, list)
                        }
                        // 当前章在读时先用 order 回退打勾的，目录回来后补记稳定 ID
                        pendingReadOrders.toList().forEach { pendingOrder ->
                            val target = list.firstOrNull { it.order == pendingOrder }
                            if (target != null && target.id.isNotBlank()) {
                                ReaderPrefs.current().markChapterRead(comicId, target.id, target.order)
                                pendingReadOrders.remove(pendingOrder)
                            }
                        }
                    } else {
                        _chaptersResolved.value = false
                        // PERF-07：离线兜底目录用内存索引，不再逐章 isDownloaded 扫目录
                        val completed = com.jmread.core.download.DownloadManager
                            .downloadedStructure.value[comicId] ?: emptySet()
                        _chapters.value = com.jmread.core.download.DownloadManager.comicTasks(comicId)
                            .filter { it.task.order in completed || it.isFinished }
                            .map { t ->
                                ComicChapter(
                                    id = "$comicId#${t.task.order}",
                                    title = t.task.epTitle.ifBlank { "第 ${t.task.order} 话" },
                                    order = t.task.order,
                                )
                            }
                    }
                    if (gen != loadGeneration) return@withContext
                    refreshReadKeysInternal()
                    observeDownloaded(comicId)

                    // 顺便拿封面/作品标题/作者（历史记录用），失败不影响阅读
                    if (_coverUrl.value.isBlank() || _comicTitle.value.isBlank() || _comicAuthor.value.isBlank()) {
                        runCatchingCancellable {
                            val detail = JmRepository.comicDetail(comicId)
                            if (gen != loadGeneration) return@withContext
                            if (_coverUrl.value.isBlank()) {
                                _coverUrl.value = detail.coverUrl.orEmpty()
                            }
                            if (_comicTitle.value.isBlank()) {
                                _comicTitle.value = detail.title
                            }
                            if (_comicAuthor.value.isBlank()) {
                                _comicAuthor.value = detail.author
                            }
                        }
                        if (gen != loadGeneration) return@withContext
                        // 离线时详情不可用：用下载任务里的标题/封面兜底，避免历史记录只剩「第 N 话」
                        if (_comicTitle.value.isBlank() || _coverUrl.value.isBlank()) {
                            val t = com.jmread.core.download.DownloadManager.comicTasks(comicId)
                                .firstOrNull()?.task
                            if (_comicTitle.value.isBlank()) _comicTitle.value = t?.comicTitle.orEmpty()
                            if (_coverUrl.value.isBlank()) _coverUrl.value = t?.coverUrl.orEmpty()
                        }
                        // 详情就绪后补写最近阅读：修正此前按兜底标题("第N话")写入的记录
                        if (lastRecordedPage >= 0) {
                            recordRecentRead(lastRecordedPage)
                        }
                    }
                }
                if (gen == loadGeneration) {
                    _epTitle.value = _chapters.value.firstOrNull { it.order == order }?.title.orEmpty()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 切章/退出时取消旧加载：必须放行，否则旧协程会继续写 _pages/_loading
                throw e
            } catch (e: Exception) {
                // 页面已在本章内尽力获取；只有彻底没拿到任何页时才落日志。
                // 绝不能像旧实现那样无条件清空：离线打开已下载章节时，
                // 章节列表请求失败会把刚读出的本地页一起抹掉。
                if (gen == loadGeneration && _pages.value.isEmpty()) {
                    LogStore.log(
                        "reader", "W",
                        "章节加载失败 comic=$comicId order=$order: ${e.message?.take(80)}",
                    )
                }
            } finally {
                PerfTrace.endAsync(pagesTrace)
                if (gen == loadGeneration && isActive) _loading.value = false
            }
        }
    }

    /** 章节列表失败后的抽屉重试入口：只重拉目录，不动当前页 */
    fun retryChapters() {
        val refNow = ref
        if (refNow.isBlank()) return
        val gen = loadGeneration
        viewModelScope.launch(Dispatchers.IO) {
            val remote = runCatchingCancellable { JmRepository.chapters(refNow) }
            if (gen != loadGeneration) return@launch
            if (remote.isSuccess) {
                val list = remote.getOrThrow()
                _chapters.value = list
                _chaptersResolved.value = true
                if (list.isNotEmpty() && list.all { it.id.isNotBlank() }) {
                    migrateOrderFallbackReads(refNow, list)
                }
                pendingReadOrders.toList().forEach { pendingOrder ->
                    val target = list.firstOrNull { it.order == pendingOrder }
                    if (target != null && target.id.isNotBlank()) {
                        ReaderPrefs.current().markChapterRead(refNow, target.id, target.order)
                        pendingReadOrders.remove(pendingOrder)
                    }
                }
                _epTitle.value = list.firstOrNull { it.order == currentOrder }?.title.orEmpty()
            } else {
                _chaptersResolved.value = false
            }
            refreshReadKeysInternal()
        }
    }

    /**
     * 目录拿到稳定 ID 后，把离线期间按 order 记下的已读勾迁移过去再清除回退标记。
     * 直接 clear 会把上次离线阅读的勾永久丢掉。
     */
    private suspend fun migrateOrderFallbackReads(ref: String, chapters: List<ComicChapter>) {
        val doc = runCatching { ReaderPrefs.current().chaptersReadDoc(ref) }.getOrNull() ?: return
        doc.orders.forEach { order ->
            val target = chapters.firstOrNull { it.order == order && it.id.isNotBlank() }
            if (target != null) {
                ReaderPrefs.current().markChapterRead(ref, target.id, order)
            }
        }
        ReaderPrefs.current().dropOrderFallbackReads(ref)
    }

    private suspend fun refreshReadKeysInternal() {
        val refNow = ref
        if (refNow.isBlank()) return
        val keys = runCatching { ReaderPrefs.current().readChapterKeysAsync(refNow) }
            .getOrDefault(emptySet())
        _readChapterKeys.value = keys
    }

    private fun observeDownloaded(comicId: String) {
        downloadedJob?.cancel()
        downloadedJob = viewModelScope.launch(Dispatchers.IO) {
            // PERF-07：订阅结构流（入队/完成/删除/恢复才变化），不再订阅整个 tasks 流。
            // 字节进度与速度更新不会触发这里，也就不会逐章 isDownloaded 扫目录。
            combine(_chapters, com.jmread.core.download.DownloadManager.downloadedStructure) { chs, structure ->
                chs to structure
            }.collectLatest { (chs, structure) ->
                val completed = structure[comicId] ?: emptySet()
                _downloadedOrders.value = chs.asSequence()
                    .map { it.order }
                    .filter { it in completed }
                    .toSet()
            }
        }
    }

    /** 当前章节的稳定 ID（章节未成功解析/离线兜底时返回 null，使用 order 回退身份） */
    private fun chapterIdFor(order: Int): String? {
        if (!_chaptersResolved.value) return null
        return _chapters.value.firstOrNull { it.order == order }
            ?.id
            ?.takeIf { it.isNotBlank() }
    }

    /** 等待 pages 加载完成后由 UI 消费的恢复页（-1 = 无需恢复）。
     *
     * 必须是 StateFlow 而非普通 var：UI 的恢复 effect 以"收集到的值 >= 0"为触发条件，
     * 只有状态本身变化才能保证 effect 在本章 pages 就绪后确定性地重启。
     * 普通 var 有两个方向的竞态——pages 赋值前发布：中间隔着网络抓取，期间任意一次重组
     * 都会用上一章的 pages 过早消费恢复页；pages 赋值后发布：若恰好没有后续重组，
     * effect 永远不重启，恢复静默丢失。 */
    private val _pendingRestorePage = MutableStateFlow(-1)
    val pendingRestorePage: StateFlow<Int> = _pendingRestorePage.asStateFlow()

    /** 恢复完成（或被新一轮加载取代）后由 UI 复位；值相同时 StateFlow 去重，
     *  消费动作本身不会反过来重启恢复 effect 打断进行中的定位校正 */
    fun consumePendingRestore() {
        _pendingRestorePage.value = -1
    }

    /** 跳转到指定章节（加载新章节页面）。恢复页复位由 load() 内统一负责 */
    fun switchChapter(context: Context, order: Int) {
        if (order < 1 || order == currentOrder) return
        loadedKey = ""
        load(context, ref, order)
    }

    /** 上一次进度落盘 Job：滚动时逐页触发，取消旧任务避免乱序覆盖（新页码覆盖旧页码） */
    private var progressJob: Job? = null

    /** 保存阅读进度（本地，带页码），并刷新"最近阅读"；同步更新已读/已读完状态。
     *
     * final=true 用于退出阅读器的兜底保存：viewModelScope 会随导航返回销毁并取消
     * 在途写盘，此时必须落到不随 VM 取消的独立作用域，否则最后 1 秒内的翻页进度丢失。 */
    fun saveProgress(pageIndex: Int, final: Boolean = false) {
        progressJob?.cancel()
        val pages = _pages.value
        if (pages.isEmpty()) return
        val safePage = pageIndex.coerceAtLeast(0)
        val orderNow = currentOrder
        val chapterId = chapterIdFor(orderNow)
        // 只有“目录已解析 + 页面来源完整”才允许判读完：
        // 离线兜底目录只含已下载章节，部分本地页也不代表章节真的读完。
        val lastOrder = if (_chaptersResolved.value) {
            _chapters.value.maxOfOrNull { it.order }
        } else {
            null
        }
        val complete = pagesComplete
        if (final) {
            val refNow = ref
            val revision = revisionAtLoad
            val reachedEnd = complete && lastOrder == orderNow && safePage >= pages.size - 1
            ReaderPrefs.current().ioScope.launch {
                val ok = runCatching {
                    ReaderPrefs.current().saveProgress(refNow, orderNow, safePage, revision)
                }.getOrDefault(false)
                runCatching {
                    ReaderPrefs.current().saveChapterProgress(refNow, chapterId, orderNow, safePage, revision)
                }
                if (!ok) {
                    // 清除阅读记录已发生：本次写入被丢弃，后续翻页用新修订号继续
                    revisionAtLoad = runCatching {
                        ReaderPrefs.current().currentRevision(refNow)
                    }.getOrDefault(revision)
                }
                com.jmread.data.ReaderStatus.markRead(refNow)
                if (reachedEnd &&
                    com.jmread.data.ReaderStatus.of(refNow) != com.jmread.data.ReadStatus.FINISHED
                ) {
                    runCatching { ReaderPrefs.current().saveFinished(refNow) }
                    com.jmread.data.ReaderStatus.markFinished(refNow)
                }
            }
            return
        }
        progressJob = viewModelScope.launch(Dispatchers.IO) {
            // runCatchingCancellable 会重新抛出取消：被后续 saveProgress 取代时立即停止，
            // 而普通写盘失败仍按原行为继续更新内存已读标记
            val ok = runCatchingCancellable {
                ReaderPrefs.current().saveProgress(ref, orderNow, safePage, revisionAtLoad)
            }.getOrDefault(false)
            runCatchingCancellable {
                ReaderPrefs.current().saveChapterProgress(
                    ref, chapterId, orderNow, safePage, revisionAtLoad,
                )
            }
            if (!ok) {
                revisionAtLoad = runCatching {
                    ReaderPrefs.current().currentRevision(ref)
                }.getOrDefault(revisionAtLoad)
            }
            // 打开过阅读器即已读（内存状态只升不降）
            com.jmread.data.ReaderStatus.markRead(ref)
            // 读到最后一章最后一页 → 已读完（持久化 + 内存标记，重复到达不重复写盘）
            if (complete && lastOrder == orderNow && safePage >= pages.size - 1) {
                if (com.jmread.data.ReaderStatus.of(ref) != com.jmread.data.ReadStatus.FINISHED) {
                    runCatchingCancellable { ReaderPrefs.current().saveFinished(ref) }
                    com.jmread.data.ReaderStatus.markFinished(ref)
                }
            }
        }
    }

    /**
     * 标记当前章已读（P1-05）。
     *
     * 只能由 UI 在“末页实际可见且图片加载成功”时调用；打开章节、预取、失败占位都不算。
     * 章节目录尚未成功解析时先写 order 回退身份，目录回来后补记稳定 chapterId。
     */
    fun markChapterRead() {
        val orderNow = currentOrder
        if (markedReadOrder == orderNow) return
        markedReadOrder = orderNow
        val chapterId = chapterIdFor(orderNow)
        if (chapterId == null) pendingReadOrders += orderNow
        val refNow = ref
        viewModelScope.launch(Dispatchers.IO) {
            ReaderPrefs.current().markChapterRead(refNow, chapterId, orderNow)
            refreshReadKeysInternal()
        }
    }

    /** 最近一次记录历史时的页码（-1 = 本次会话尚未记录过；详情就绪后据此补写） */
    private var lastRecordedPage: Int = -1

    /** 上一次"最近阅读"落盘 Job：与 progressJob 同理，防抖防乱序 */
    private var recentJob: Job? = null

    /**
     * 记录最近阅读条目（首页"继续阅读"用）。
     * 立即落盘（标题未就绪时先写兜底值）；详情加载完成后由 load() 触发补写覆盖，
     * 避免直接进章节、详情未返回就退出时在历史里留下孤立的"第N话"。
     */
    fun recordRecentRead(pageIndex: Int, final: Boolean = false) {
        // 页面还没加载出来时不要写历史：否则打开章节立刻切后台会留下 page 0，
        // 覆盖书架里真实的续读位置。
        if (_pages.value.isEmpty()) return
        lastRecordedPage = pageIndex.coerceAtLeast(0)
        recentJob?.cancel()
        val refNow = ref
        val orderNow = currentOrder
        val pageNow = lastRecordedPage
        if (final) {
            // 同 saveProgress(final=true)：退出兜底写入不随 viewModelScope 取消
            ReaderPrefs.current().ioScope.launch {
                runCatchingCancellable {
                    ReaderPrefs.current().recordRecentRead(
                        ref = refNow,
                        title = _comicTitle.value.ifBlank { "第 $orderNow 话" },
                        coverUrl = _coverUrl.value,
                        author = _comicAuthor.value,
                        order = orderNow,
                        pageIndex = pageNow,
                    )
                }
                // 书架（阅读历史参与排序/续读行）即时反映本次阅读
                com.jmread.core.library.LibraryRepository.notifyLocalChanged()
            }
            return
        }
        recentJob = viewModelScope.launch(Dispatchers.IO) {
            runCatchingCancellable {
                ReaderPrefs.current().recordRecentRead(
                    ref = refNow,
                    title = _comicTitle.value.ifBlank { "第 $orderNow 话" },
                    coverUrl = _coverUrl.value,
                    author = _comicAuthor.value,
                    order = orderNow,
                    pageIndex = pageNow,
                )
            }
            com.jmread.core.library.LibraryRepository.notifyLocalChanged()
        }
    }

    /**
     * 预取前后 N 页图片到内存/磁盘缓存（弱网也顺滑）。
     *
     * 必须与展示请求使用**相同的解码尺寸**：此前预取不带 .size()，
     * Coil 会按原图尺寸解码，且显式 memoryCacheKey(url) 与展示请求共用同一缓存键，
     * 导致超大长图以原图进入内存缓存、解码上限失效（OOM 风险）。
     * 低内存设备或系统压力回调后 range 降为 0，只保留当前页。
     */
    fun preloadNearby(
        context: Context,
        visiblePage: Int,
        scrollMode: Boolean,
        range: Int = 2,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        val flinging = lastPreloadCallAt > 0 && now - lastPreloadCallAt < FLING_PAUSE_MS
        lastPreloadCallAt = now

        val chapterKey = "$ref:$currentOrder"
        val identityChanged = preloadChapterKey != chapterKey || preloadScrollMode != scrollMode
        val pressure = MemoryBudget.shouldDisablePrefetch()
        if (identityChanged || pressure) {
            disposePreloadWindow()
            preloadChapterKey = chapterKey
            preloadScrollMode = scrollMode
            if (pressure) return
        }
        // fling 中不动窗口：既不新增也不抖动式重建，停止后下一次调用收敛
        if (flinging && !identityChanged) return

        val pages = _pages.value
        if (pages.isEmpty()) return
        val loader = Coil.imageLoader(context)
        val size = if (scrollMode) webtoonDecodeSize(context) else pagerDecodeSize(context)
        // 后退最多 1 页；前进最多 2 页（range 只能收紧，不能放开）
        val forward = range.coerceIn(1, 2)
        val windowStart = (visiblePage - 1).coerceAtLeast(0)
        val windowEnd = (visiblePage + forward).coerceAtMost(pages.size - 1)
        val desired = windowStart..windowEnd

        // 只 dispose 离开窗口的项；交集保留，在途传输不被打断
        for (page in preloadWindow.keys.toList()) {
            if (page !in desired) {
                preloadWindow.remove(page)?.let { runCatching { it.dispose() } }
            }
        }
        for (page in desired) {
            if (preloadWindow.containsKey(page)) continue
            val url = pages.getOrNull(page)?.imageUrl ?: continue
            // 本地文件（已下载）无需预取
            if (url.startsWith("file:")) continue
            // 与展示请求共用同一缓存身份（含自动/强制还原模式；重试次数不进入 key）；
            // #prefetch 只影响传输调度优先级，不进入缓存身份、不发给 CDN
            val cacheKey = com.jmread.core.scramble.jmImageCacheKey(url)
            preloadWindow[page] = loader.enqueue(
                ImageRequest.Builder(context)
                    .data(com.jmread.core.scramble.jmPrefetchUrl(url))
                    .size(size)
                    // 显式 Size + AUTOMATIC 会把小图放大到目标尺寸；阅读器只缩不放
                    .precision(Precision.INEXACT)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                    .build()
            )
        }
    }

    override fun onCleared() {
        // 退出阅读器后不再保留全尺寸图片下载/解码
        disposePreloadWindow()
        super.onCleared()
    }

    private companion object {
        /** 快速 fling 判定窗口：两次翻页回调间隔小于该值时暂停新增预取 */
        const val FLING_PAUSE_MS = 250L
    }
}
