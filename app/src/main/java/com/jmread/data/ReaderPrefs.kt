package com.jmread.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

private val Context.readerDataStore by preferencesDataStore(name = "jm_reader")

private object ReaderKeys {
    const val PROGRESS_PREFIX = "progress_"
    const val FINISHED_PREFIX = "finished_"
    const val READER_MODE = "reader_mode"
    const val BRIGHTNESS = "reader_brightness"
    const val RECENT_READS = "recent_reads"
    const val HIDE_BOTTOM_BAR = "hide_bottom_bar_in_reader"
}

/** 最近阅读条目（首页"继续阅读"用） */
@kotlinx.serialization.Serializable
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
 * 阅读器偏好：本地阅读进度 / 阅读模式 / 亮度。
 *
 * 云端进度同步不可行（当前数据源 API 无进度接口），本地进度保证换章、重进可续读。
 */
class ReaderPrefs private constructor(private val appContext: Context) {

    companion object {
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

    suspend fun saveProgress(ref: String, order: Int, pageIndex: Int) {
        progressCache[ref] = Progress(order, pageIndex)
        appContext.readerDataStore.edit {
            it[stringPreferencesKey(ReaderKeys.PROGRESS_PREFIX + ref)] = "$order:$pageIndex"
        }
    }

    /** 标记作品已读完（读到最后一章最后一页时调用；幂等，重复写无副作用） */
    suspend fun saveFinished(ref: String) {
        appContext.readerDataStore.edit {
            it[stringPreferencesKey(ReaderKeys.FINISHED_PREFIX + ref)] = "1"
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

    // ── 热点值内存缓存：避免 getter/setter 在主线程 runBlocking 读盘 ──────
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

    /** 记录/刷新最近阅读（最近 12 条，按时间倒序）。[ref] 为作品标识 */
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
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<RecentRead>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val entry = RecentRead(ref, title, coverUrl, author, order, pageIndex, System.currentTimeMillis())
            val updated = (listOf(entry) + current.filterNot { it.ref == ref })
                .take(12)
            prefs[key] = json.encodeToString(updated)
            cachedRecentReads = updated
        }
    }
}
