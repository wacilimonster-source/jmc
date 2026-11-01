package com.jmread.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.readerDataStore by preferencesDataStore(name = "jm_reader")

private object ReaderKeys {
    const val PROGRESS_PREFIX = "progress_"
    const val FINISHED_PREFIX = "finished_"
    const val CHAPTER_PROGRESS_PREFIX = "chapter_progress_"
    const val CHAPTERS_READ_PREFIX = "chapters_read_"
    const val REVISION_PREFIX = "revision_"
    const val READER_MODE = "reader_mode"
    const val BRIGHTNESS = "reader_brightness"
    const val RECENT_READS = "recent_reads"
    const val HIDE_BOTTOM_BAR = "hide_bottom_bar_in_reader"
    const val VOLUME_KEY_PAGING = "volume_key_paging"
    const val CHAPTER_LIST_DESC = "chapter_list_desc"
    const val READER_GUIDE_SHOWN = "reader_guide_shown"
}

/** 最近阅读条目（首页"继续阅读"用） */
@Serializable
data class RecentRead(
    val comicId: String,
    val title: String,
    val coverUrl: String,
    val author: String = "",
    val order: Int,
    val pageIndex: Int,
    val ts: Long,
) {
    /** 作品标识（单源 == comicId） */
    val ref: String get() = comicId
}

/**
 * 单章进度。key 为稳定 chapterId；缺少稳定 ID 时使用 `order:{order}` 回退身份。
 * order 同时保留，用于旧数据兼容与页面数变化时的边界裁剪。
 */
@Serializable
data class ChapterProgressEntry(
    val key: String,
    val order: Int,
    val pageIndex: Int,
)

@Serializable
data class ChapterProgressDoc(
    val version: Int = 1,
    val entries: List<ChapterProgressEntry> = emptyList(),
)

/** 已读章节：稳定 ID 与 order 回退身份分开存，避免重排把旧勾转移给另一章。 */
@Serializable
data class ChaptersReadDoc(
    val version: Int = 1,
    val chapterIds: List<String> = emptyList(),
    val orders: List<Int> = emptyList(),
) {
    val keys: Set<String>
        get() = chapterIds.toSet() + orders.map { orderKey(it) }

    companion object {
        fun orderKey(order: Int): String = "order:$order"
        fun keyOf(chapterId: String?, order: Int): String =
            chapterId?.takeIf { it.isNotBlank() } ?: orderKey(order)
    }
}

/** 清除阅读记录前的完整快照（P1-08 撤销用；瞬态，不持久化）。 */
data class ReadHistorySnapshot(
    val comicId: String,
    val recentRead: RecentRead?,
    val progress: ReaderPrefs.Progress?,
    val chapterProgress: ChapterProgressDoc?,
    val chaptersRead: ChaptersReadDoc?,
    val finished: Boolean,
    val revision: Long,
    val clearedAt: Long = System.currentTimeMillis(),
)

/**
 * 阅读器偏好：本地阅读进度 / 阅读模式 / 亮度 / 每章进度 / 已读章节。
 *
 * 云端进度同步不可行（当前数据源 API 无进度接口），本地进度保证换章、重进可续读。
 *
 * 修订号（revision）用于串行化「清除阅读记录」与在途异步写盘：
 * 写盘方携带读取时的修订号，清除时先递增修订号；携带旧号的迟到写入直接丢弃，
 * 不会在清除后把历史/进度偷偷写回来。
 */
class ReaderPrefs private constructor(private val appContext: Context) {

    companion object {
        /** 历史记录上限：超过后淘汰最旧（阅读进度不随历史淘汰） */
        private const val MAX_RECENT_READS = 100

        private lateinit var instance: ReaderPrefs

        fun init(context: Context) {
            instance = ReaderPrefs(context.applicationContext)
            // 与 SourcePrefs 同一范式：后台预热热点值，避免 getter 在主线程 runBlocking 读盘。
            // 此前 ReaderPrefs 没有任何预热，而 readerMode / brightness 是在**组合期**同步读的
            // （ReaderScreen / SettingsScreen 的 remember 初值），冷启动首次进入必然主线程做文件 IO。
            instance.ioScope.launch { instance.loadCache() }
        }

        fun current(): ReaderPrefs = instance
    }

    /** 后台预热：只填热点值，recentReads 体积大仍走懒加载 */
    private fun loadCache() {
        runCatching {
            runBlocking {
                val prefs = appContext.readerDataStore.data.first()
                cachedReaderMode = prefs[intPreferencesKey(ReaderKeys.READER_MODE)]
                cachedBrightness = prefs[floatPreferencesKey(ReaderKeys.BRIGHTNESS)]
            }
        }
    }

    /** 后台写盘作用域：setter 只更新内存缓存后投递到这里，绝不在调用线程同步等待落盘。
     *
     * 公开给阅读器做"退出兜底保存"：viewModelScope 会随导航返回销毁并取消在途写盘，
     * 最后一段进度必须落到这个不随 VM 取消的作用域才能保证落盘。 */
    val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * 阅读进度：作品标识 -> (order, pageIndex)；pageIndex 从 0 开始。
     *
     * 单源 App，键直接用漫画 id，无源前缀。
     */
    data class Progress(val order: Int, val pageIndex: Int)

    // 进程内进度缓存：读进度走内存，saveProgress 时同步回写，避免每次读盘
    private val progressCache = java.util.concurrent.ConcurrentHashMap<String, Progress>()
    private val chapterProgressCache = java.util.concurrent.ConcurrentHashMap<String, ChapterProgressDoc>()
    private val chaptersReadCache = java.util.concurrent.ConcurrentHashMap<String, ChaptersReadDoc>()
    private val revisionCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 所有进度/已读写入与清除共用；保证同一作品的读改写不交错 */
    private val writeMutex = Mutex()

    /** 已读章节版本的全局信号：详情页/抽屉据此刷新勾选 */
    private val _chapterReadVersion = MutableStateFlow(0)
    val chapterReadVersion: StateFlow<Int> = _chapterReadVersion.asStateFlow()

    // ── 作品最近进度（兼容旧键；comicId 级"上次读到哪"） ──────────────────
    fun lastProgress(ref: String): Progress? {
        progressCache[ref]?.let { return it }
        val raw = runCatching {
            runBlocking { readProgressRaw(ref) }
        }.getOrNull() ?: return null
        return parseProgress(raw)?.also { progressCache[ref] = it }
    }

    /** 协程上下文中的推荐入口：挂起读盘，不阻塞调用线程 */
    suspend fun lastProgressAsync(ref: String): Progress? {
        progressCache[ref]?.let { return it }
        val raw = runCatching { readProgressRaw(ref) }.getOrNull() ?: return null
        return parseProgress(raw)?.also { progressCache[ref] = it }
    }

    private suspend fun readProgressRaw(ref: String): String? {
        val prefs = appContext.readerDataStore.data.first()
        return prefs[stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + ref)]
    }

    private fun parseProgress(raw: String): Progress? {
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val order = parts[0].toIntOrNull() ?: return null
        val page = parts[1].toIntOrNull() ?: return null
        return Progress(order, page)
    }

    /**
     * 保存作品最近进度。
     * [revision] 非空时做修订号校验：清除阅读记录后，携带旧号的迟到写入直接丢弃。
     * @return false = 因修订号过期被丢弃
     */
    suspend fun saveProgress(
        ref: String,
        order: Int,
        pageIndex: Int,
        revision: Long? = null,
    ): Boolean = writeMutex.withLock {
        if (revision != null && revision != currentRevisionLocked(ref)) return@withLock false
        progressCache[ref] = Progress(order, pageIndex)
        appContext.readerDataStore.edit {
            it[stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + ref)] = "$order:$pageIndex"
        }
        true
    }

    // ── 每章进度（P0-01/P1-08） ────────────────────────────────────────────

    /** 按 order 读取本章进度；无每章记录时只对旧作品进度对应章节做一次迁移。 */
    suspend fun chapterProgressAsync(ref: String, chapterId: String?, order: Int): Int? {
        val doc = chapterProgressDoc(ref)
        val key = ChaptersReadDoc.keyOf(chapterId, order)
        doc.entries.firstOrNull { it.key == key }?.let { return it.pageIndex }
        doc.entries.firstOrNull { it.order == order }?.let { return it.pageIndex }
        // 旧数据迁移：只在没有该章记录时，把旧作品进度补进对应章节
        val legacy = runCatching { lastProgressAsync(ref) }.getOrNull() ?: return null
        if (legacy.order != order) return null
        runCatching { saveChapterProgress(ref, chapterId, order, legacy.pageIndex) }
        return legacy.pageIndex
    }

    suspend fun saveChapterProgress(
        ref: String,
        chapterId: String?,
        order: Int,
        pageIndex: Int,
        revision: Long? = null,
    ) {
        writeMutex.withLock {
            if (revision != null && revision != currentRevisionLocked(ref)) return@withLock
            val doc = loadChapterProgressDoc(ref)
            val key = ChaptersReadDoc.keyOf(chapterId, order)
            // 有稳定 ID 时移除同 order 的回退身份，避免一条章两个键
            val entries = doc.entries
                .filterNot { it.key == key || it.order == order }
                .plus(ChapterProgressEntry(key = key, order = order, pageIndex = pageIndex))
                .sortedBy { it.order }
            val updated = doc.copy(entries = entries)
            chapterProgressCache[ref] = updated
            appContext.readerDataStore.edit {
                it[stringPreferencesKey(ReaderKeys.CHAPTER_PROGRESS_PREFIX + ref)] =
                    json.encodeToString(updated)
            }
        }
    }

    suspend fun chapterProgressDoc(ref: String): ChapterProgressDoc =
        writeMutex.withLock { loadChapterProgressDoc(ref) }

    private suspend fun loadChapterProgressDoc(ref: String): ChapterProgressDoc {
        chapterProgressCache[ref]?.let { return it }
        val raw = runCatching {
            appContext.readerDataStore.data.first()
                .get(stringPreferencesKey(ReaderKeys.CHAPTER_PROGRESS_PREFIX + ref))
        }.getOrNull()
        val doc = raw?.let {
            runCatching { json.decodeFromString<ChapterProgressDoc>(it) }.getOrDefault(ChapterProgressDoc())
        } ?: ChapterProgressDoc()
        chapterProgressCache[ref] = doc
        return doc
    }

    // ── 已读章节（P1-05） ─────────────────────────────────────────────────

    /** 已读身份集合：稳定 chapterId + `order:N` 回退键 */
    suspend fun readChapterKeysAsync(ref: String): Set<String> = chaptersReadDoc(ref).keys

    /** 同步读缓存（详情页/抽屉展示用；首次未预热返回空集，后台会再刷新） */
    fun readChapterKeysCached(ref: String): Set<String> =
        chaptersReadCache[ref]?.keys.orEmpty()

    suspend fun chaptersReadDoc(ref: String): ChaptersReadDoc =
        writeMutex.withLock { loadChaptersReadDoc(ref) }

    private suspend fun loadChaptersReadDoc(ref: String): ChaptersReadDoc {
        chaptersReadCache[ref]?.let { return it }
        val raw = runCatching {
            appContext.readerDataStore.data.first()
                .get(stringPreferencesKey(ReaderKeys.CHAPTERS_READ_PREFIX + ref))
        }.getOrNull()
        val doc = raw?.let {
            runCatching { json.decodeFromString<ChaptersReadDoc>(it) }.getOrDefault(ChaptersReadDoc())
        } ?: ChaptersReadDoc()
        chaptersReadCache[ref] = doc
        return doc
    }

    /** 标记某章已读完（稳定 ID 优先；无 ID 时用 order 回退，并清掉同 order 的不确定旧键） */
    suspend fun markChapterRead(ref: String, chapterId: String?, order: Int) {
        writeMutex.withLock {
            val doc = loadChaptersReadDoc(ref)
            val stable = chapterId?.takeIf { it.isNotBlank() }
            val ids = if (stable != null) {
                (doc.chapterIds + stable).distinct()
            } else {
                doc.chapterIds
            }
            val orders = if (stable != null) {
                // 稳定 ID 可确认身份后，移除同 order 的旧回退标记，防止重排后错误点亮
                doc.orders - order
            } else {
                (doc.orders + order).distinct()
            }
            val updated = doc.copy(chapterIds = ids, orders = orders)
            if (updated == doc) return@withLock
            chaptersReadCache[ref] = updated
            appContext.readerDataStore.edit {
                it[stringPreferencesKey(ReaderKeys.CHAPTERS_READ_PREFIX + ref)] =
                    json.encodeToString(updated)
            }
            _chapterReadVersion.value++
        }
    }

    /**
     * 目录加载后调用：若所有章节都有稳定 ID，则清除 order 回退身份
     * （旧回退标记无法可靠对应到重排后的章节，宁可少一个勾也不转错）。
     */
    suspend fun dropOrderFallbackReads(ref: String) {
        writeMutex.withLock {
            val doc = loadChaptersReadDoc(ref)
            if (doc.orders.isEmpty()) return@withLock
            val updated = doc.copy(orders = emptyList())
            chaptersReadCache[ref] = updated
            appContext.readerDataStore.edit {
                it[stringPreferencesKey(ReaderKeys.CHAPTERS_READ_PREFIX + ref)] =
                    json.encodeToString(updated)
            }
            _chapterReadVersion.value++
        }
    }

    // ── 修订号 ────────────────────────────────────────────────────────────

    suspend fun currentRevision(ref: String): Long =
        writeMutex.withLock { currentRevisionLocked(ref) }

    private suspend fun currentRevisionLocked(ref: String): Long {
        revisionCache[ref]?.let { return it }
        val value = runCatching {
            appContext.readerDataStore.data.first()[longPreferencesKey(ReaderKeys.REVISION_PREFIX + ref)]
        }.getOrNull() ?: 0L
        revisionCache[ref] = value
        return value
    }

    /** 递增修订号：所有在途旧写入随即失效 */
    suspend fun bumpRevision(ref: String): Long = writeMutex.withLock {
        val next = currentRevisionLocked(ref) + 1
        revisionCache[ref] = next
        appContext.readerDataStore.edit {
            it[longPreferencesKey(ReaderKeys.REVISION_PREFIX + ref)] = next
        }
        next
    }

    /** 标记作品已读完（读到最后一章最后一页时调用；幂等，重复写无副作用） */
    suspend fun saveFinished(ref: String) {
        appContext.readerDataStore.edit {
            it[stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref)] = "1"
        }
    }

    suspend fun clearFinished(ref: String) {
        appContext.readerDataStore.edit {
            it.remove(stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref))
        }
    }

    /**
     * 清除单部作品的进度与读完标记（书架「清除阅读记录」用；历史条目由调用方另行删除）。
     * 先递增修订号，随后删除；在此前发出的迟到写盘会携带旧号被丢弃。
     */
    suspend fun clearProgress(ref: String) {
        writeMutex.withLock {
            val next = currentRevisionLocked(ref) + 1
            revisionCache[ref] = next
            progressCache.remove(ref)
            chapterProgressCache.remove(ref)
            chaptersReadCache.remove(ref)
            appContext.readerDataStore.edit {
                it[longPreferencesKey(ReaderKeys.REVISION_PREFIX + ref)] = next
                it.remove(stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + ref))
                it.remove(stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref))
                it.remove(stringPreferencesKey(ReaderKeys.CHAPTER_PROGRESS_PREFIX + ref))
                it.remove(stringPreferencesKey(ReaderKeys.CHAPTERS_READ_PREFIX + ref))
            }
            _chapterReadVersion.value++
        }
    }

    // ── 撤销恢复（P1-08） ─────────────────────────────────────────────────

    /** 按快照恢复单部作品；调用方负责保证快照未被后续再次清除。 */
    suspend fun restoreSnapshot(snapshot: ReadHistorySnapshot) {
        writeMutex.withLock {
            val ref = snapshot.comicId
            val next = currentRevisionLocked(ref) + 1
            revisionCache[ref] = next

            // 撤销期间用户可能已经重新阅读：清除后产生的进度/读完/已读集合优先保留
            val currentProgress = progressCache[ref]
                ?: runCatching { readProgressRaw(ref) }.getOrNull()?.let { parseProgress(it) }
            val currentChapter = loadChapterProgressDoc(ref)
            val currentRead = loadChaptersReadDoc(ref)
            val currentFinished = runCatching {
                appContext.readerDataStore.data.first()[
                    stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref)
                ] != null
            }.getOrDefault(false)

            val progressToWrite = currentProgress ?: snapshot.progress
            val chapterToWrite = if (currentProgress != null) {
                currentChapter.takeIf { it.entries.isNotEmpty() }
            } else {
                snapshot.chapterProgress
            }
            val readToWrite = mergeReadDocs(currentRead, snapshot.chaptersRead)
            val finishedToWrite = currentFinished || snapshot.finished

            appContext.readerDataStore.edit { prefs ->
                prefs[longPreferencesKey(ReaderKeys.REVISION_PREFIX + ref)] = next
                val progressKey = stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + ref)
                if (progressToWrite != null) {
                    prefs[progressKey] = "${progressToWrite.order}:${progressToWrite.pageIndex}"
                } else {
                    prefs.remove(progressKey)
                }
                val finishedKey = stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref)
                if (finishedToWrite) prefs[finishedKey] = "1" else prefs.remove(finishedKey)

                val cpKey = stringPreferencesKey(ReaderKeys.CHAPTER_PROGRESS_PREFIX + ref)
                if (chapterToWrite != null && chapterToWrite.entries.isNotEmpty()) {
                    prefs[cpKey] = json.encodeToString(chapterToWrite)
                } else {
                    prefs.remove(cpKey)
                }
                val crKey = stringPreferencesKey(ReaderKeys.CHAPTERS_READ_PREFIX + ref)
                if (readToWrite != null && readToWrite.keys.isNotEmpty()) {
                    prefs[crKey] = json.encodeToString(readToWrite)
                } else {
                    prefs.remove(crKey)
                }
            }
            progressToWrite?.let { progressCache[ref] = it } ?: progressCache.remove(ref)
            chapterToWrite?.let { chapterProgressCache[ref] = it }
                ?: chapterProgressCache.remove(ref)
            readToWrite?.let { chaptersReadCache[ref] = it }
                ?: chaptersReadCache.remove(ref)
            _chapterReadVersion.value++
        }
    }

    private fun mergeReadDocs(
        current: ChaptersReadDoc,
        snapshot: ChaptersReadDoc?,
    ): ChaptersReadDoc? {
        val ids = (current.chapterIds + snapshot?.chapterIds.orEmpty()).distinct()
        val orders = (current.orders + snapshot?.orders.orEmpty()).distinct()
        return if (ids.isEmpty() && orders.isEmpty()) {
            null
        } else {
            ChaptersReadDoc(chapterIds = ids, orders = orders)
        }
    }

    /** 是否已标记整本读完（撤销合并用） */
    suspend fun isFinished(ref: String): Boolean = runCatching {
        appContext.readerDataStore.data.first()[
            stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref)
        ] != null
    }.getOrDefault(false)

    /** 构建完整快照（清除前调用）；返回 null 表示该作品没有任何可恢复记录。 */
    suspend fun snapshotForClear(ref: String): ReadHistorySnapshot {
        val recent = recentReadsAsync().firstOrNull { it.ref == ref }
        val progress = lastProgressAsync(ref)
        val chapterProgress = chapterProgressDoc(ref)
        val chaptersRead = chaptersReadDoc(ref)
        val finished = isFinished(ref)
        val revision = currentRevision(ref)
        return ReadHistorySnapshot(
            comicId = ref,
            recentRead = recent,
            progress = progress,
            chapterProgress = chapterProgress.takeIf { it.entries.isNotEmpty() },
            chaptersRead = chaptersRead.takeIf { it.keys.isNotEmpty() },
            finished = finished,
            revision = revision,
        )
    }

    // ── 开关与偏好 ────────────────────────────────────────────────────────

    // 热点值内存缓存：避免 getter/setter 在主线程 runBlocking 读盘
    @Volatile private var cachedReaderMode: Int? = null
    @Volatile private var cachedBrightness: Float? = null
    @Volatile private var cachedRecentReads: List<RecentRead>? = null

    // 高频写盘合并 Job：亮度滑条逐帧触发 setter，用取消+重投递把 60 次/秒的写盘合并成 1 次
    private var readerModeJob: kotlinx.coroutines.Job? = null
    private var brightnessJob: kotlinx.coroutines.Job? = null

    /** 阅读模式：0=滚动流（条漫），1=横滑翻页 */
    var readerMode: Int
        get() = cachedReaderMode
            ?: runCatching {
                runBlocking {
                    appContext.readerDataStore.data.first()[intPreferencesKey(ReaderKeys.READER_MODE)]
                }
            }.getOrNull() ?: 0
        set(value) {
            cachedReaderMode = value
            // 单次事件：投递后台落盘，不阻塞调用线程（此前为 runBlocking 同步写）
            readerModeJob?.cancel()
            readerModeJob = ioScope.launch {
                runCatching {
                    appContext.readerDataStore.edit {
                        it[intPreferencesKey(ReaderKeys.READER_MODE)] = value
                    }
                }
            }
        }

    /** 阅读亮度（1.0 为原亮度，越小越暗） */
    var brightness: Float
        get() = cachedBrightness
            ?: runCatching {
                runBlocking {
                    appContext.readerDataStore.data.first()[floatPreferencesKey(ReaderKeys.BRIGHTNESS)]
                }
            }.getOrNull() ?: 1.0f
        set(value) {
            val v = value.coerceIn(0.2f, 1.0f)
            cachedBrightness = v
            // 关键修复：Slider 拖动时逐帧回调，此前 runBlocking 会让主线程每帧同步等落盘，
            // 表现为滑条严重卡顿（低端机可 ANR）。改为内存即时生效 + 后台合并落盘。
            brightnessJob?.cancel()
            brightnessJob = ioScope.launch {
                delay(300) // 拖动停止 300ms 后才落盘，合并高频写
                runCatching {
                    appContext.readerDataStore.edit {
                        it[floatPreferencesKey(ReaderKeys.BRIGHTNESS)] = v
                    }
                }
            }
        }

    /**
     * 阅读时是否隐藏底部导航栏（默认开）。
     *
     * 以 Flow 暴露，MainScreen 可直接 collectAsState，改设置后无需重启即时生效。
     */
    val hideBottomBarInReader: Flow<Boolean> = appContext.readerDataStore.data
        .map { it[booleanPreferencesKey(ReaderKeys.HIDE_BOTTOM_BAR)] ?: true }
        .distinctUntilChanged()

    suspend fun setHideBottomBarInReader(enabled: Boolean) {
        appContext.readerDataStore.edit {
            it[booleanPreferencesKey(ReaderKeys.HIDE_BOTTOM_BAR)] = enabled
        }
    }

    /** 音量键翻页（默认关） */
    val volumeKeyPaging: Flow<Boolean> = appContext.readerDataStore.data
        .map { it[booleanPreferencesKey(ReaderKeys.VOLUME_KEY_PAGING)] ?: false }
        .distinctUntilChanged()

    suspend fun setVolumeKeyPaging(enabled: Boolean) {
        appContext.readerDataStore.edit {
            it[booleanPreferencesKey(ReaderKeys.VOLUME_KEY_PAGING)] = enabled
        }
    }

    /** 章节列表倒序（默认正序） */
    val chapterListDescending: Flow<Boolean> = appContext.readerDataStore.data
        .map { it[booleanPreferencesKey(ReaderKeys.CHAPTER_LIST_DESC)] ?: false }
        .distinctUntilChanged()

    suspend fun setChapterListDescending(descending: Boolean) {
        appContext.readerDataStore.edit {
            it[booleanPreferencesKey(ReaderKeys.CHAPTER_LIST_DESC)] = descending
        }
    }

    /** 首次点击分区引导是否已展示（默认 false） */
    val readerGuideShown: Flow<Boolean> = appContext.readerDataStore.data
        .map { it[booleanPreferencesKey(ReaderKeys.READER_GUIDE_SHOWN)] ?: false }
        .distinctUntilChanged()

    suspend fun setReaderGuideShown(shown: Boolean) {
        appContext.readerDataStore.edit {
            it[booleanPreferencesKey(ReaderKeys.READER_GUIDE_SHOWN)] = shown
        }
    }

    // ── 最近阅读（首页"继续阅读"） ─────────────────────────────────────────
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun recentReads(): List<RecentRead> {
        cachedRecentReads?.let { return it }
        val list = runCatching {
            runBlocking {
                val raw = appContext.readerDataStore.data.first()
                    .get(stringPreferencesKey(ReaderKeys.RECENT_READS))
                if (raw.isNullOrBlank()) return@runBlocking emptyList()
                runCatching {
                    json.decodeFromString<List<RecentRead>>(raw)
                }.getOrDefault(emptyList())
            }
        }.getOrDefault(emptyList())
        cachedRecentReads = list
        return list
    }

    /** 协程上下文中的推荐入口：挂起读盘，不阻塞调用线程（冷启动首次读取涉及文件 IO） */
    suspend fun recentReadsAsync(): List<RecentRead> {
        cachedRecentReads?.let { return it }
        val list = runCatching {
            val raw = appContext.readerDataStore.data.first()
                .get(stringPreferencesKey(ReaderKeys.RECENT_READS))
            if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<RecentRead>>(raw) }.getOrDefault(emptyList())
        }.getOrDefault(emptyList())
        cachedRecentReads = list
        return list
    }

    /** 记录/刷新最近阅读（最近 [MAX_RECENT_READS] 条，按时间倒序）。[ref] 为作品标识 */
    suspend fun recordRecentRead(
        ref: String,
        title: String,
        coverUrl: String,
        author: String,
        order: Int,
        pageIndex: Int,
    ) {
        appContext.readerDataStore.edit { prefs ->
            val key = stringPreferencesKey(ReaderKeys.RECENT_READS)
            val current = decodeRecent(prefs[key])
            val entry = RecentRead(ref, title, coverUrl, author, order, pageIndex, System.currentTimeMillis())
            val updated = (listOf(entry) + current.filterNot { it.ref == ref })
                .take(MAX_RECENT_READS)
            prefs[key] = json.encodeToString(updated)
            cachedRecentReads = updated
        }
    }

    /** 删除单条阅读历史（按作品标识；不影响阅读进度/已读完状态） */
    suspend fun deleteRecentRead(ref: String) {
        appContext.readerDataStore.edit { prefs ->
            val key = stringPreferencesKey(ReaderKeys.RECENT_READS)
            val updated = decodeRecent(prefs[key]).filterNot { it.ref == ref }
            prefs[key] = json.encodeToString(updated)
            cachedRecentReads = updated
        }
    }

    /** 清空阅读历史（不影响阅读进度/已读完状态） */
    suspend fun clearRecentReads() {
        appContext.readerDataStore.edit { prefs ->
            val key = stringPreferencesKey(ReaderKeys.RECENT_READS)
            prefs[key] = json.encodeToString(emptyList<RecentRead>())
            cachedRecentReads = emptyList()
        }
    }

    /**
     * 撤销删除/清空：把快照合并回当前列表。
     *
     * 以当前列表优先（同作品取当前记录），避免撤销期间刚产生的阅读记录被旧快照覆盖；
     * 合并后按时间倒序并保持上限。
     */
    suspend fun restoreRecentReads(entries: List<RecentRead>) {
        if (entries.isEmpty()) return
        appContext.readerDataStore.edit { prefs ->
            val key = stringPreferencesKey(ReaderKeys.RECENT_READS)
            val current = decodeRecent(prefs[key])
            val merged = (entries + current)
                .associateBy { it.ref }
                .values
                .sortedByDescending { it.ts }
                .take(MAX_RECENT_READS)
            prefs[key] = json.encodeToString(merged)
            cachedRecentReads = merged
        }
    }

    /**
     * 一次性读取全部进度/读完标记（启动预热内存缓存用；finished_ 优先于 progress_，避免被降级覆盖）。
     *
     * 返回键为作品标识。
     */
    suspend fun loadAllStatuses(): Map<String, ReadStatus> = runCatching {
        val prefs = appContext.readerDataStore.data.first()
        val result = mutableMapOf<String, ReadStatus>()
        // 先处理 progress_，再处理 finished_：后者无条件覆盖，与迭代顺序无关
        prefs.asMap().forEach { (key, _) ->
            if (key.name.startsWith(ReaderKeys.PROGRESS_PREFIX)) {
                result[canonicalRef(key.name.removePrefix(ReaderKeys.PROGRESS_PREFIX))] = ReadStatus.READ
            }
        }
        prefs.asMap().forEach { (key, _) ->
            if (key.name.startsWith(ReaderKeys.FINISHED_PREFIX)) {
                result[canonicalRef(key.name.removePrefix(ReaderKeys.FINISHED_PREFIX))] = ReadStatus.FINISHED
            }
        }
        result
    }.getOrDefault(emptyMap())

    private fun canonicalRef(stored: String): String = stored

    private fun decodeRecent(raw: String?): List<RecentRead> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<RecentRead>>(raw) }
            .getOrDefault(emptyList())
    }
}
