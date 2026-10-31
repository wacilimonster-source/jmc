package com.jmread.core.library

import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.core.log.LogStore
import com.jmread.core.model.ComicSummary
import com.jmread.data.Bookmarks
import com.jmread.data.FavouriteSyncQueue
import com.jmread.data.ReaderPrefs
import com.jmread.data.ReaderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 统一书架仓库（docs/unified-library-design.md §4）：
 * 四源并集合成 + 云端懒加载 + 收藏双写 + 同步队列编排。
 *
 * 现有存储（Bookmarks/ReaderPrefs/ReaderStatus）结构零改动——存量数据天然迁移；
 * 本对象是合成层，不是新的真相源。
 */
object LibraryRepository {

    /** 云端部分状态：收藏列表 + 历史列表，各自按页懒加载 */
    data class CloudState(
        val favourites: List<ComicSummary> = emptyList(),
        val history: List<ComicSummary> = emptyList(),
        val favPageLoaded: Int = 0,
        val favPagesTotal: Int = 1,
        val historyPageLoaded: Int = 0,
        val historyPagesTotal: Int = 1,
        val loading: Boolean = false,
        val error: String? = null,
        /** 本轮登录会话已加载过（登出→登录切换后强制重拉） */
        val sessionLoaded: Boolean = false,
    ) {
        /** 未登录/未加载时视为已到底：本地-only 列表不该触发懒加载，也能显示页脚 */
        val endReached: Boolean
            get() = !sessionLoaded ||
                (favPageLoaded >= favPagesTotal && historyPageLoaded >= historyPagesTotal)
    }

    private val _cloud = MutableStateFlow(CloudState())
    val cloud: StateFlow<CloudState> = _cloud.asStateFlow()

    /** 本地变更通知：阅读器存进度/书架清除记录后 bump，驱动合并重算 */
    private val localVersion = MutableStateFlow(0)

    fun notifyLocalChanged() {
        localVersion.value++
    }

    /** 由 JmApp.onCreate 调用：提前装配 items 合并流（combine + stateIn 首次求值涉及 DataStore 读取） */
    fun init() {
        // stateIn(Eagerly) 在 object 首次引用时即启动；显式触碰一次确保装配时机在 App 启动
        items.value
    }

    /**
     * 统一书架条目流：本地书架 ∪ 本地历史 ∪ 云端收藏 ∪ 云端历史。
     * ReaderStatus.version / localVersion 作为触发器参与 combine。
     */
    val items: StateFlow<List<LibraryItem>> = combine(
        Bookmarks.items,
        ReaderStatus.version,
        localVersion,
        _cloud,
    ) { bookmarks, _, _, cloudState ->
        LibraryMerge.merge(
            bookmarks = bookmarks,
            recents = ReaderPrefs.current().recentReadsAsync(),
            cloudFavourites = cloudState.favourites,
            cloudHistory = cloudState.history,
            statusOf = { ReaderStatus.of(it) },
        )
    }.stateIn(com.jmread.core.AppScope.IO, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    // ---------- 云端加载 ----------

    private val cloudMutex = Mutex()

    /** 进入书架/下拉刷新时调用：已登录则拉云端两列表首页，失败不阻塞本地数据 */
    suspend fun refreshCloud(force: Boolean = false) {
        if (!JmRepository.isLoggedIn || !JmCapabilities.hasCloudFavouriteRead) {
            _cloud.value = CloudState()
            return
        }
        if (!force && _cloud.value.sessionLoaded) return
        cloudMutex.withLock {
            if (!force && _cloud.value.sessionLoaded) return
            _cloud.value = _cloud.value.copy(loading = true, error = null)
            var firstError: String? = null
            val favs = runCatching { JmRepository.favourites(1) }
                .onFailure { firstError = firstError ?: it.message }
                .getOrNull()
            val hist = runCatching { JmRepository.cloudHistory(1) }
                .onFailure { firstError = firstError ?: it.message }
                .getOrNull()
            if (favs == null && hist == null) {
                _cloud.value = _cloud.value.copy(
                    loading = false,
                    error = firstError ?: "云端数据同步失败",
                )
                return
            }
            _cloud.value = CloudState(
                favourites = favs?.items ?: emptyList(),
                favPageLoaded = if (favs != null) 1 else 0,
                favPagesTotal = favs?.pages?.coerceAtLeast(1) ?: 1,
                history = hist?.items ?: emptyList(),
                historyPageLoaded = if (hist != null) 1 else 0,
                historyPagesTotal = hist?.pages?.coerceAtLeast(1) ?: 1,
                loading = false,
                error = if (favs == null || hist == null) (firstError ?: "云端数据同步失败") else null,
                sessionLoaded = true,
            )
        }
    }

    /** 触底懒加载：交替补收藏/历史的下一页，合并进并集（按 id 去重） */
    suspend fun loadMoreCloud() {
        if (!JmRepository.isLoggedIn) return
        cloudMutex.withLock {
            val cs = _cloud.value
            if (cs.loading || cs.endReached) return
            _cloud.value = cs.copy(loading = true)
            var error: String? = null
            var favs: com.jmread.core.model.PageResult<ComicSummary>? = null
            var hist: com.jmread.core.model.PageResult<ComicSummary>? = null
            if (cs.favPageLoaded < cs.favPagesTotal) {
                favs = runCatching { JmRepository.favourites(cs.favPageLoaded + 1) }
                    .onFailure { error = it.message }
                    .getOrNull()
            } else if (cs.historyPageLoaded < cs.historyPagesTotal) {
                hist = runCatching { JmRepository.cloudHistory(cs.historyPageLoaded + 1) }
                    .onFailure { error = it.message }
                    .getOrNull()
            }
            _cloud.value = _cloud.value.let { cur ->
                cur.copy(
                    favourites = favs?.let { (cur.favourites + it.items).distinctBy { s -> s.id } } ?: cur.favourites,
                    favPageLoaded = favs?.page ?: cur.favPageLoaded,
                    favPagesTotal = favs?.pages?.coerceAtLeast(1) ?: cur.favPagesTotal,
                    history = hist?.let { (cur.history + it.items).distinctBy { s -> s.id } } ?: cur.history,
                    historyPageLoaded = hist?.page ?: cur.historyPageLoaded,
                    historyPagesTotal = hist?.pages?.coerceAtLeast(1) ?: cur.historyPagesTotal,
                    loading = false,
                    error = error ?: cur.error,
                )
            }
        }
    }

    // ---------- 收藏双写（详情页心形与书架卡片共用） ----------

    sealed interface CollectResult {
        /** 本地与云端已一致（含云端本就无需翻转的情况） */
        data object Synced : CollectResult

        /** 未登录：仅本地生效 */
        data object LocalOnly : CollectResult

        /** 云端失败已入同步队列，稍后自动补 */
        data class Queued(val message: String) : CollectResult
    }

    /**
     * 收藏/取消收藏到目标态（幂等，心形一次点击只调一次）。
     *
     * 顺序：本地权威先行（即时生效、离线可用）→ 已登录则对账后翻转云端；
     * 云端侧任何失败都只入队，不影响本地结果（翻转端点非幂等，绝不盲翻）。
     */
    suspend fun setCollected(
        comicId: String,
        target: Boolean,
        title: String,
        author: String,
        coverUrl: String,
        note: String = "",
    ): CollectResult = withContext(Dispatchers.IO) {
        // 1. 本地先行
        if (target) {
            Bookmarks.add(Bookmarks.BookmarkItem(comicId, title, author, coverUrl, System.currentTimeMillis(), note))
        } else {
            Bookmarks.remove(comicId)
        }
        // 2. 云端尽力
        if (!JmRepository.isLoggedIn || !JmCapabilities.hasCloudFavouriteWrite) return@withContext CollectResult.LocalOnly
        val cloudIds = runCatching { JmRepository.cloudFavouriteIds() }.getOrElse {
            FavouriteSyncQueue.enqueue(comicId, target)
            return@withContext CollectResult.Queued("已${if (target) "收藏" else "取消收藏"}，云端同步稍后自动完成")
        }
        if (cloudIds.contains(comicId) == target) return@withContext CollectResult.Synced
        val now = runCatching { JmRepository.favourite(comicId) }.getOrElse {
            FavouriteSyncQueue.enqueue(comicId, target)
            return@withContext CollectResult.Queued("已${if (target) "收藏" else "取消收藏"}，云端同步稍后自动完成")
        }
        if (now == target) return@withContext CollectResult.Synced
        // 服务端终态与意图不一致（缓存过期/并发翻转）：以服务端为准回填本地，保证心形全局一致
        LogStore.log("Library", "W", "收藏翻转终态与意图不符：comic=$comicId 意图=$target 服务端=$now，以服务端回填本地")
        if (now) {
            Bookmarks.add(Bookmarks.BookmarkItem(comicId, title, author, coverUrl, System.currentTimeMillis(), note))
        } else {
            Bookmarks.remove(comicId)
        }
        CollectResult.Synced
    }

    // ---------- 同步队列 ----------

    /**
     * flush 收藏同步队列（启动/登录/下拉刷新时调用）。
     * 未登录直接跳过（意图留在磁盘，下次登录续传）。
     */
    suspend fun flushPendingSync(): FavouriteSyncQueue.FlushReport? {
        if (!JmRepository.isLoggedIn || !JmCapabilities.hasCloudFavouriteWrite) return null
        if (FavouriteSyncQueue.pending.value.isEmpty()) return null
        return FavouriteSyncQueue.flush(
            reconcile = { JmRepository.cloudFavouriteIds(force = true) },
            toggle = { id -> JmRepository.favourite(id) },
        )
    }

    // ---------- 登录 backfill（本地书架 → 云端，一次性确认后执行） ----------

    /** 需要推送到云端的本地收藏条数（backfill 确认弹窗用）；云端集合拉取失败返回 0（不打扰） */
    suspend fun backfillPendingCount(): Int {
        if (!JmRepository.isLoggedIn || !JmCapabilities.hasCloudFavouriteWrite) return 0
        val cloud = runCatching { JmRepository.cloudFavouriteIds() }.getOrNull() ?: return 0
        return Bookmarks.items.value.count { it.comicId !in cloud }
    }

    /** 执行 backfill，返回 (成功, 失败)；失败的条目自动入同步队列后续补 */
    suspend fun backfillToCloud(): Pair<Int, Int> {
        if (!JmRepository.isLoggedIn || !JmCapabilities.hasCloudFavouriteWrite) return 0 to 0
        val cloud = runCatching { JmRepository.cloudFavouriteIds(force = true) }.getOrNull() ?: return 0 to 0
        val missing = Bookmarks.items.value.filter { it.comicId !in cloud }
        var ok = 0
        var fail = 0
        for (b in missing) {
            val now = runCatching { JmRepository.favourite(b.comicId) }.getOrNull()
            if (now == true) {
                ok++
            } else {
                fail++
                FavouriteSyncQueue.enqueue(b.comicId, add = true)
            }
        }
        LogStore.log("Library", "I", "backfill 完成：同步 $ok 本，失败 $fail 本（已入队）")
        return ok to fail
    }

    // ---------- 清除阅读记录 ----------

    /**
     * 清除单部作品的本地阅读痕迹（历史条目 + 进度 + 读完标记）。
     * 收藏态不动；云端历史无删除端点，不在能力范围内。
     */
    suspend fun clearReadHistory(comicId: String) = withContext(Dispatchers.IO) {
        ReaderPrefs.current().deleteRecentRead(comicId)
        ReaderPrefs.current().clearProgress(comicId)
        ReaderStatus.clear(comicId)
        notifyLocalChanged()
    }
}
