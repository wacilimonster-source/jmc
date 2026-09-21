package com.jmread.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.RankTab
import com.jmread.core.model.WeekPeriod
import com.jmread.core.model.sortedByComicSort
import com.jmread.data.AuthorFavourites
import com.jmread.data.FollowSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 关注来源类型 */
private enum class FollowTargetType { AUTHOR, KEYWORD }

/** 排行榜数据过期阈值：回前台时超过该时长则静默重拉 */
private const val RANK_REFRESH_TTL_MS = 10 * 60 * 1000L

/** 一个关注来源：作者 / 组合关键词（空格连接）+ 可选标签 */
private data class FollowTarget(
    val key: String,
    val type: FollowTargetType,
    val name: String,
    val tag: String? = null,
)

/** 相邻关注来源之间的请求间隔（本源限速宽松，间隔只为礼貌） */
private const val TARGET_REQUEST_INTERVAL_MS = 300L

/** 一次关注流拉取的结果 */
private data class FollowFetchResult(
    val items: List<ComicSummary>,
    /** 本轮未成功拉取的来源数量 */
    val failedCount: Int,
)

/**
 * 首页数据聚合：排行榜(H24/D7/D30) + 关注信息流 + 每周必看。
 * 关注流 = 所有关注来源（收藏作者/关键词/分类标签）的最新作品合并，
 * 按更新时间由近至远排序、按 id 去重，滚动加载（每个来源逐页拉取）。
 */
class HomeViewModel : ViewModel() {

    private val _followFeed = MutableStateFlow<List<ComicSummary>>(emptyList())
    val followFeed: StateFlow<List<ComicSummary>> = _followFeed.asStateFlow()

    private val _followEndReached = MutableStateFlow(false)
    val followEndReached: StateFlow<Boolean> = _followEndReached.asStateFlow()

    private val _followLoading = MutableStateFlow(false)
    val followLoading: StateFlow<Boolean> = _followLoading.asStateFlow()

    private val _followEmptyHint = MutableStateFlow<String?>(null)
    val followEmptyHint: StateFlow<String?> = _followEmptyHint.asStateFlow()

    private val _followError = MutableStateFlow<String?>(null)
    val followError: StateFlow<String?> = _followError.asStateFlow()

    /** 关注流每次刷新完成 +1（UI 据此回到列表顶部） */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    private var lastAutoRefreshAt = 0L
    private var everRefreshed = false

    /** 冷启动首屏刷新：只执行一次 */
    fun initialRefresh() {
        if (everRefreshed) return
        everRefreshed = true
        refresh()
    }

    fun refreshOnResume() {
        val now = System.currentTimeMillis()
        if (rankLoadedAt > 0 && now - rankLoadedAt >= RANK_REFRESH_TTL_MS) {
            loadRank(_rankType.value, force = true, startDelayMs = 2_000)
        }
        if (now - lastAutoRefreshAt < 30_000) return
        everRefreshed = true
        refresh()
    }

    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val rankComics: StateFlow<List<ComicSummary>> = _rankComics.asStateFlow()

    /**
     * 默认档位 = 新晋热榜（H24）。
     *
     * 历史：这里曾默认月榜，因为 2026-09-21 实测线上 `o=mv_t`（日）与 `o=mv_w`（周）
     * 都返回 total=0。后续复测发现 **周榜已恢复**（`o=mv_w` 稳定 32 条），
     * 只有日榜 `o=mv_t` 是真死（参考库 jm_config.py 的 `ORDER_DAY_RANKING='mv_t'` 确认参数没错）。
     * 现在 H24 不再直连服务端日榜，而是由月榜数据本地重排得到（见 JmRepository.newArrivals），
     * 因此可以安全地当默认档位 —— 它不会为空。
     */
    private val _rankType = MutableStateFlow(RankTab.H24.value)
    val rankType: StateFlow<String> = _rankType.asStateFlow()

    /**
     * 当前可用的榜单档位。
     *
     * 初值是「乐观全集」：先按三档渲染，避免等探测回来才出 tab（首屏空白）。
     * 探测完成后收敛到线上真有数据的档位；若正选中的档位被判定不可用，自动切到首个可用档位。
     * 详见 [JmRepository.availableRankTabs]。
     */
    private val _rankTabs = MutableStateFlow(RankTab.optimistic)
    val rankTabs: StateFlow<List<RankTab>> = _rankTabs.asStateFlow()

    private var rankTabsProbed = false

    /** 探测榜单档位可用性。失败时保持乐观全集：宁可多一个空档位，也不藏掉能用的档位。 */
    fun ensureRankTabs() {
        if (rankTabsProbed) return
        rankTabsProbed = true
        viewModelScope.launch {
            val tabs = runCatching { JmRepository.availableRankTabs() }
                .getOrDefault(RankTab.optimistic)
            if (tabs.isEmpty()) return@launch
            _rankTabs.value = tabs
            if (tabs.none { it.value == _rankType.value }) {
                loadRank(tabs.first().value, force = true)
            }
        }
    }

    private val _rankLoading = MutableStateFlow(false)
    val rankLoading: StateFlow<Boolean> = _rankLoading.asStateFlow()

    private val _rankError = MutableStateFlow<String?>(null)
    val rankError: StateFlow<String?> = _rankError.asStateFlow()

    private var rankLoadedAt = 0L

    private val _rankRefreshTick = MutableStateFlow(0)
    val rankRefreshTick: StateFlow<Int> = _rankRefreshTick.asStateFlow()

    /** 排行榜真实命中数（禁漫独有：日/周/月榜 total 真实） */
    private val _rankTotal = MutableStateFlow(0)
    val rankTotal: StateFlow<Int> = _rankTotal.asStateFlow()

    // ---- 每周必看 ----
    private val _weekPeriods = MutableStateFlow<List<WeekPeriod>>(emptyList())
    val weekPeriods: StateFlow<List<WeekPeriod>> = _weekPeriods.asStateFlow()

    private val _weekSelected = MutableStateFlow<WeekPeriod?>(null)
    val weekSelected: StateFlow<WeekPeriod?> = _weekSelected.asStateFlow()

    private val _weekComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val weekComics: StateFlow<List<ComicSummary>> = _weekComics.asStateFlow()

    private val _weekLoading = MutableStateFlow(false)
    val weekLoading: StateFlow<Boolean> = _weekLoading.asStateFlow()

    private val _weekError = MutableStateFlow<String?>(null)
    val weekError: StateFlow<String?> = _weekError.asStateFlow()

    private var weekLoaded = false

    /** 各关注来源当前已加载到的页数（key -> page） */
    private var targetPages = mutableMapOf<String, Int>()

    /** 各关注来源是否已到末页 */
    private var targetEnded = mutableMapOf<String, Boolean>()

    private var targets: List<FollowTarget> = emptyList()

    private var followLoadingJob: kotlinx.coroutines.Job? = null

    /** 各 Tab 滚动位置恢复 */
    private var _savedFollowIndex = 0
    val savedFollowIndex: Int get() = _savedFollowIndex

    private var _savedRankIndex = 0
    val savedRankIndex: Int get() = _savedRankIndex

    private var _savedWeekIndex = 0
    val savedWeekIndex: Int get() = _savedWeekIndex

    private val _isScrollStateRestored = MutableStateFlow(false)
    val isScrollStateRestored: StateFlow<Boolean> = _isScrollStateRestored

    fun saveScrollState(tab: Int, index: Int) {
        when (tab) {
            0 -> _savedFollowIndex = index
            1 -> _savedRankIndex = index
            2 -> _savedWeekIndex = index
        }
    }

    fun markScrollStateRestored() {
        _isScrollStateRestored.value = true
    }

    init {
        _followFeed.value = com.jmread.data.FollowFeedCache.load()
            .map { fillUpdatedAt(it) }
        viewModelScope.launch {
            com.jmread.data.UpdatedAtCache.version.collect {
                val current = _followFeed.value
                val refilled = current.map { fillUpdatedAt(it) }
                if (refilled != current) _followFeed.value = refilled
            }
        }
    }

    /** 排行榜请求代际：快速切榜时旧响应不得覆盖新选择 */
    private var rankGeneration = 0

    fun loadRank(type: String, force: Boolean = false, startDelayMs: Long = 0) {
        if (!force && _rankType.value == type && _rankComics.value.isNotEmpty() && _rankError.value == null) return
        val gen = ++rankGeneration
        _rankType.value = type
        if (!force && _rankComics.value.isNotEmpty()) _rankComics.value = emptyList()
        _rankLoading.value = true
        _rankError.value = null
        viewModelScope.launch {
            try {
                if (startDelayMs > 0) kotlinx.coroutines.delay(startDelayMs)
                val result = JmRepository.rank(type)
                if (gen != rankGeneration) return@launch
                _rankComics.value = result.items
                _rankTotal.value = result.total
                rankLoadedAt = System.currentTimeMillis()
                _rankRefreshTick.value++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != rankGeneration) return@launch
                if (!force) _rankComics.value = emptyList()
                _rankError.value = e.message ?: "加载排行榜失败"
            } finally {
                if (gen == rankGeneration) _rankLoading.value = false
            }
        }
    }

    /** 首次进入关注 tab：构建关注来源列表 */
    fun ensureFollowTargets() {
        if (targets.isNotEmpty()) return
        rebuildTargets()
    }

    private fun rebuildTargets() {
        targets = buildList {
            AuthorFavourites.get().forEach { add(FollowTarget("a_${it.author}", FollowTargetType.AUTHOR, it.author)) }
            FollowSettings.items().forEach { item ->
                val name = item.keywords.joinToString(" ")
                add(FollowTarget("k_$name|${item.tag ?: ""}", FollowTargetType.KEYWORD, name, item.tag))
            }
        }
    }

    /** 下拉刷新：所有来源重新从第 1 页拉取，取前120条 */
    fun refresh() {
        lastAutoRefreshAt = System.currentTimeMillis()
        rebuildTargets()
        if (targets.isEmpty()) {
            _followFeed.value = emptyList()
            _followEndReached.value = true
            _followEmptyHint.value = "还没有关注内容，去「我的 → 关注管理」添加作者或关键词关注"
            _followError.value = null
            com.jmread.data.FollowFeedCache.clear()
            return
        }
        _followEmptyHint.value = null
        _followLoading.value = true
        _followError.value = null
        followLoadingJob?.cancel()
        followLoadingJob = viewModelScope.launch {
            try {
                targetPages.clear()
                targetEnded.clear()
                val result = fetchTargetPage(1)
                val totalFailed = result.failedCount
                if (result.items.isNotEmpty() || totalFailed == 0) {
                    mergeIntoFeed(result.items, append = totalFailed > 0)
                    if (totalFailed == 0) _refreshTick.value++
                    if (_followFeed.value.size > 120) {
                        _followFeed.value = _followFeed.value.take(120)
                    }
                    runCatching { com.jmread.data.FollowFeedCache.save(_followFeed.value) }
                }
                _followEndReached.value = true
                _followError.value = when {
                    totalFailed > 0 && result.items.isEmpty() ->
                        "全部关注来源拉取失败，已保留上次内容"
                    totalFailed > 0 ->
                        "${totalFailed} 个关注来源暂时拉取失败，内容可能不完整"
                    else -> null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _followError.value = "刷新失败（${e.message ?: "网络错误"}），已展示上次缓存"
            } finally {
                if (isActive) _followLoading.value = false
            }
        }
    }

    /** 首次进入每周必看 tab：加载期数列表并选中最新一期 */
    fun ensureWeekLoaded() {
        if (weekLoaded) return
        weekLoaded = true
        _weekLoading.value = true
        _weekError.value = null
        viewModelScope.launch {
            try {
                val periods = JmRepository.weeklyPeriods()
                if (_weekPeriods.value.isEmpty() && periods.isNotEmpty()) {
                    _weekPeriods.value = periods
                    if (_weekSelected.value == null) selectWeek(periods.first())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _weekError.value = e.message ?: "每周必看加载失败"
            } finally {
                _weekLoading.value = false
            }
        }
    }

    /** 选择某期：拉取该期内容流 */
    fun selectWeek(period: WeekPeriod?) {
        if (period == null) return
        _weekSelected.value = period
        _weekComics.value = emptyList()
        _weekLoading.value = true
        _weekError.value = null
        viewModelScope.launch {
            try {
                val result = JmRepository.weeklyContent(period.id, 1)
                _weekComics.value = result.items
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _weekError.value = e.message ?: "该期内容加载失败"
            } finally {
                _weekLoading.value = false
            }
        }
    }

    /** 拉取所有来源的指定页（串行，来源之间留间隔） */
    private suspend fun fetchTargetPage(page: Int): FollowFetchResult {
        val result = mutableListOf<ComicSummary>()
        var failed = 0
        var requested = false
        val ordered = targets.sortedBy {
            if (it.type == FollowTargetType.KEYWORD && it.name.isNotBlank() && it.name.split(Regex("\\s+")).size > 1) 1 else 0
        }
        val pending = ordered.filter { targetEnded[it.key] != true }
        for (target in pending) {
            if (requested) kotlinx.coroutines.delay(TARGET_REQUEST_INTERVAL_MS)
            requested = true
            result += try {
                when (target.type) {
                    FollowTargetType.AUTHOR ->
                        searchWithRetry(target.name, page, emptyList())
                            .also { r ->
                                targetPages[target.key] = page
                                if (page >= r.pages) targetEnded[target.key] = true
                            }.items
                    FollowTargetType.KEYWORD ->
                        fetchKeywordPage(target, page)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                targetEnded[target.key] = true
                failed++
                emptyList()
            }
        }
        return FollowFetchResult(result, failed)
    }

    /**
     * 组合关键词拉取：每个词分别全文搜索取 id 交集（"且"关系）。
     * 关注项带标签时，每词搜索都带分类 slug（c=，服务端精确筛选）。
     */
    private suspend fun fetchKeywordPage(
        target: FollowTarget,
        startPage: Int,
    ): List<ComicSummary> {
        val categories = if (target.tag == null) emptyList() else listOf(target.tag)
        val words = target.name.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        if (words.isEmpty()) {
            targetPages[target.key] = startPage
            targetEnded[target.key] = true
            return emptyList()
        }
        if (words.size <= 1 && target.tag == null) {
            val result = searchWithRetry(target.name, startPage, emptyList())
            targetPages[target.key] = startPage
            if (startPage >= result.pages) targetEnded[target.key] = true
            return result.items
        }
        // 多词或单词+标签：每词拉取全部页（上限 25 页，浏览流硬上限 125 页内取子集）取交集
        if (startPage > 1) {
            targetEnded[target.key] = true
            return emptyList()
        }
        val wordPageCounts: List<Pair<String, Int>> = words.map { word ->
            val first = com.jmread.core.runCatchingCancellable {
                searchWithRetry(word, 1, categories)
            }.getOrNull()
            word to (first?.pages ?: 1).coerceIn(1, 25)
        }
        val wordSets: List<List<ComicSummary>> = wordPageCounts.map { (word, pages) ->
            (1..pages).mapNotNull { p ->
                com.jmread.core.runCatchingCancellable {
                    searchWithRetry(word, p, categories)
                }.getOrNull()?.items
            }.flatten()
        }
        val wordIds = wordSets.map { set -> set.map { it.id }.toSet() }
        val common = wordIds[0].filter { id -> wordIds.all { it.contains(id) } }
        targetPages[target.key] = 25
        targetEnded[target.key] = true
        val firstSetById = wordSets[0].associateBy { it.id }
        return common
            .mapNotNull { firstSetById[it] }
            .sortedByComicSort(ComicSort.DD)
    }

    /** 单词/多词搜索，失败自动重试 */
    private suspend fun searchWithRetry(
        word: String,
        page: Int,
        categories: List<String> = emptyList(),
    ): com.jmread.core.model.PageResult<ComicSummary> {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return JmRepository.search(word, page, ComicSort.DD, categories = categories)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                if (attempt < 2) kotlinx.coroutines.delay(500)
            }
        }
        throw last ?: RuntimeException("search failed")
    }

    /** 合并新拉取的漫画：按 id 去重、按更新时间（ISO 前缀字典序）由近至远排序 */
    private fun mergeIntoFeed(newItems: List<ComicSummary>, append: Boolean = false) {
        val base = if (append) _followFeed.value else emptyList()
        val merged = (base + newItems)
            .distinctBy { it.id }
            .map { fillUpdatedAt(it) }
            .sortedByDescending { it.updatedAt }
        _followFeed.value = merged
        if (merged.isEmpty() && !append) {
            _followEmptyHint.value = "关注的内容暂无更新"
        }
    }

    private fun fillUpdatedAt(item: ComicSummary): ComicSummary {
        if (item.updatedAt.isNotBlank()) {
            com.jmread.data.UpdatedAtCache.put(item.ref, item.updatedAt)
            return item
        }
        return com.jmread.data.UpdatedAtCache.of(item.ref)?.let { item.copy(updatedAt = it) } ?: item
    }
}
