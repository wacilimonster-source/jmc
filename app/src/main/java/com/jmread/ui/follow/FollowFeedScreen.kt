package com.jmread.ui.follow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.sortedByComicSort
import com.jmread.data.AuthorFavourites
import com.jmread.data.FollowFeedCache
import com.jmread.data.FollowSettings
import com.jmread.data.GridSettings
import com.jmread.data.UpdatedAtCache
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.common.ComicGridSkeleton
import com.jmread.ui.common.EmptyState
import com.jmread.ui.common.ErrorRetry
import com.jmread.ui.common.InlineErrorBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 关注来源类型 */
private enum class FollowTargetType { AUTHOR, KEYWORD }

/** 一个关注来源：作者 / 组合关键词（空格连接）+ 可选标签 */
private data class FollowTarget(
    val key: String,
    val type: FollowTargetType,
    val name: String,
    val tag: String? = null,
)

/** 关键词交集搜索的一个数据源：mainTag=0 关键词 / 2 作者 / 3 官方标签 */
private data class SearchSource(val mainTag: Int, val query: String)

/** 相邻关注来源之间的请求间隔（本源限速宽松，间隔只为礼貌） */
private const val TARGET_REQUEST_INTERVAL_MS = 300L

/** 关注流是按 120 条截断的聚合流；交集只需取有限页，避免一次刷新打出上百个请求 */
private const val MAX_FOLLOW_INTERSECTION_PAGES = 10

/** 一次关注流拉取的结果 */
private data class FollowFetchResult(
    val items: List<ComicSummary>,
    /** 本轮未成功拉取的来源数量 */
    val failedCount: Int,
)

/** 关注流按来源分页推进；缓存只保留前 120 条控制体积，内存列表不再硬截断 */
class FollowFeedViewModel : ViewModel() {

    private val _feed = MutableStateFlow<List<ComicSummary>>(emptyList())
    val feed: StateFlow<List<ComicSummary>> = _feed.asStateFlow()

    /** 首屏默认 true：避免首次刷新开始前闪一帧「暂无关注内容」 */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 触底加载中（与下拉刷新分开：只驱动网格尾部转圈） */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    private val _emptyHint = MutableStateFlow<String?>(null)
    val emptyHint: StateFlow<String?> = _emptyHint.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 刷新完成 +1（UI 据此回到列表顶部） */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    /** 「已看到」基线：离开关注 Tab 时推进，列表用 updatedAt 与此比较显示新内容圆点 */
    private val _seenMarker = MutableStateFlow(FollowSettings.feedSeenMaxUpdatedAt())
    val seenMarker: StateFlow<String> = _seenMarker.asStateFlow()

    fun markFeedSeen() {
        val max = _feed.value.maxOfOrNull { it.updatedAt } ?: ""
        if (max.isNotBlank()) {
            FollowSettings.markFeedSeen(max)
            _seenMarker.value = max
        }
    }

    private var lastAutoRefreshAt = 0L
    private var everRefreshed = false

    /** 各来源已加载到的页数 / 是否到末页 / 连续失败次数 */
    private var targetPages = mutableMapOf<String, Int>()
    private var targetEnded = mutableMapOf<String, Boolean>()
    private var targetFailures = mutableMapOf<String, Int>()
    private var targets: List<FollowTarget> = emptyList()
    private var loadingJob: kotlinx.coroutines.Job? = null
    private var loadingMoreJob: kotlinx.coroutines.Job? = null

    init {
        // 冷启动：先展示上次成功刷新的缓存，后台静默刷新替换
        _feed.value = FollowFeedCache.load().map { fillUpdatedAt(it) }
        viewModelScope.launch {
            UpdatedAtCache.version.collect {
                val current = _feed.value
                val refilled = current.map { fillUpdatedAt(it) }
                if (refilled != current) _feed.value = refilled
            }
        }
    }

    fun initialRefresh() {
        if (everRefreshed) return
        everRefreshed = true
        refresh()
    }

    /** 回前台 30s 节流刷新 */
    fun refreshOnResume() {
        if (System.currentTimeMillis() - lastAutoRefreshAt < 30_000) return
        everRefreshed = true
        refresh()
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

    fun ensureTargets() {
        if (targets.isEmpty()) rebuildTargets()
    }

    /** 下拉刷新：所有来源重新从第 1 页拉取；之后触底按来源游标继续深入 */
    fun refresh() {
        lastAutoRefreshAt = System.currentTimeMillis()
        rebuildTargets()
        if (targets.isEmpty()) {
            loadingJob?.cancel()
            loadingMoreJob?.cancel()
            _loading.value = false
            _loadingMore.value = false
            _feed.value = emptyList()
            _endReached.value = true
            _emptyHint.value = "还没有关注内容，去「我的 → 关注管理」添加作者或关键词关注"
            _error.value = null
            FollowFeedCache.clear()
            return
        }
        _emptyHint.value = null
        _loading.value = true
        _error.value = null
        // 刷新接管：取消在途触底加载，避免旧页号写进新游标
        loadingMoreJob?.cancel()
        _loadingMore.value = false
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            try {
                targetPages.clear()
                targetEnded.clear()
                targetFailures.clear()
                val result = fetchNextTargetPage()
                val failed = result.failedCount
                if (result.items.isNotEmpty() || failed == 0) {
                    // 部分来源失败时只增不减，避免一次抖动让列表整体缩水
                    mergeIntoFeed(result.items, append = failed > 0)
                    if (failed == 0) _refreshTick.value++
                    // 缓存只存前 120 条；内存列表完整保留，加载更多继续追加
                    runCatching { FollowFeedCache.save(_feed.value) }
                }
                _endReached.value = targets.all { targetEnded[it.key] == true }
                android.util.Log.i(
                    "FollowFeed",
                    "refresh feed=${_feed.value.size} added=${result.items.size} failed=$failed end=${_endReached.value}",
                )
                _error.value = when {
                    failed > 0 && result.items.isEmpty() -> "全部关注来源拉取失败，已保留上次内容"
                    failed > 0 -> "${failed} 个关注来源暂时拉取失败，内容可能不完整"
                    else -> null
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "刷新失败（${e.message ?: "网络错误"}），已展示上次缓存"
            } finally {
                if (isActive) _loading.value = false
            }
        }
    }

    /** 触底加载更多：每个未到末页的来源推进一页，合并去重后追加 */
    fun loadMore() {
        if (_loading.value || _loadingMore.value) return
        if (_endReached.value) return
        if (targets.isEmpty()) return
        _loadingMore.value = true
        loadingMoreJob = viewModelScope.launch {
            try {
                val result = fetchNextTargetPage()
                if (result.items.isNotEmpty()) mergeIntoFeed(result.items, append = true)
                _endReached.value = targets.all { targetEnded[it.key] == true }
                android.util.Log.i(
                    "FollowFeed",
                    "loadMore feed=${_feed.value.size} added=${result.items.size} failed=${result.failedCount} end=${_endReached.value}",
                )
                _error.value = when {
                    result.failedCount > 0 ->
                        "${result.failedCount} 个关注来源加载失败，滚动到底将自动重试"
                    else -> null
                }
                runCatching { FollowFeedCache.save(_feed.value) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "加载更多失败（${e.message ?: "网络错误"}）"
            } finally {
                if (isActive) _loadingMore.value = false
            }
        }
    }

    /**
     * 拉取所有未到末页来源的下一页。每个来源独立游标，来源之间保持间隔，
     * 单次失败不立刻判死（连续 3 次才停止），避免一次网络抖动让整个关注源消失。
     */
    private suspend fun fetchNextTargetPage(): FollowFetchResult {
        val result = mutableListOf<ComicSummary>()
        var failed = 0
        var requested = false
        val ordered = targets.sortedBy {
            if (it.type == FollowTargetType.KEYWORD && it.name.isNotBlank() && it.name.split(Regex("\\s+")).size > 1) 1 else 0
        }
        for (target in ordered.filter { targetEnded[it.key] != true }) {
            if (requested) kotlinx.coroutines.delay(TARGET_REQUEST_INTERVAL_MS)
            requested = true
            val page = (targetPages[target.key] ?: 0) + 1
            result += try {
                val items = when (target.type) {
                    FollowTargetType.AUTHOR ->
                        searchWithRetry(target.name, page, mainTag = 2)
                            .also { r ->
                                targetPages[target.key] = page
                                if (page >= r.pages) targetEnded[target.key] = true
                            }.items
                    FollowTargetType.KEYWORD ->
                        fetchKeywordPage(target, page).also {
                            targetPages[target.key] = page
                        }
                }
                targetFailures.remove(target.key)
                items
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val failures = (targetFailures[target.key] ?: 0) + 1
                targetFailures[target.key] = failures
                if (failures >= 3) targetEnded[target.key] = true
                failed++
                emptyList()
            }
        }
        return FollowFetchResult(result, failed)
    }

    /**
     * 组合关键词：每词分别全文搜索取 id 交集（"且"关系）。
     *
     * 标签不是分类：官方标签词表必须走 main_tag=3（标签维度搜索）后再与关键词结果取交集。
     * 旧实现把标签塞进 /search 的 c=（分类 slug）参数，标签关注因此长期静默为空。
     */
    private suspend fun fetchKeywordPage(target: FollowTarget, startPage: Int): List<ComicSummary> {
        val words = target.name.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.isEmpty()) {
            targetPages[target.key] = startPage
            targetEnded[target.key] = true
            return emptyList()
        }
        if (words.size <= 1 && target.tag == null) {
            val result = searchWithRetry(target.name, startPage, mainTag = 0)
            targetPages[target.key] = startPage
            if (startPage >= result.pages) targetEnded[target.key] = true
            return result.items
        }
        if (startPage > 1) {
            targetEnded[target.key] = true
            return emptyList()
        }

        // 关键词用 mainTag=0；标签用 mainTag=3。首页结果复用，避免同一页请求两次。
        val sources = buildList {
            words.forEach { add(SearchSource(mainTag = 0, query = it)) }
            target.tag?.takeIf { it.isNotBlank() }?.let { add(SearchSource(mainTag = 3, query = it)) }
        }
        val firstResults = sources.map { src ->
            src to com.jmread.core.runCatchingCancellable {
                searchWithRetry(src.query, 1, src.mainTag)
            }.getOrNull()
        }
        // 任一数据源首页失败都按「该关注来源本轮失败」处理：否则交集为空会被误当成
        // 「没有匹配作品」，用户完全看不到失败提示。
        if (firstResults.any { it.second == null }) {
            throw IllegalStateException("关注来源搜索失败（关键词/标签）")
        }

        val resultSets = firstResults.map { (src, first) ->
            val pages = (first?.pages ?: 1).coerceIn(1, MAX_FOLLOW_INTERSECTION_PAGES)
            buildList {
                first?.items?.let { addAll(it) }
                for (p in 2..pages) {
                    com.jmread.core.runCatchingCancellable {
                        searchWithRetry(src.query, p, src.mainTag)
                    }.getOrNull()?.let { addAll(it.items) }
                }
            }
        }
        val idSets = resultSets.map { set -> set.map { it.id }.toSet() }
        val common = idSets.firstOrNull()
            ?.filter { id -> idSets.all { it.contains(id) } }
            .orEmpty()
        targetPages[target.key] = 25
        targetEnded[target.key] = true
        val firstSetById = resultSets.firstOrNull().orEmpty().associateBy { it.id }
        return common.mapNotNull { firstSetById[it] }.sortedByComicSort(ComicSort.DD)
    }

    private suspend fun searchWithRetry(
        word: String,
        page: Int,
        mainTag: Int = 0,
    ): com.jmread.core.model.PageResult<ComicSummary> {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return JmRepository.searchDimension(word, page, ComicSort.DD, mainTag = mainTag)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                if (attempt < 2) kotlinx.coroutines.delay(500)
            }
        }
        throw last ?: RuntimeException("search failed")
    }

    private fun mergeIntoFeed(newItems: List<ComicSummary>, append: Boolean = false) {
        val base = if (append) _feed.value else emptyList()
        val merged = (base + newItems)
            .distinctBy { it.id }
            .map { fillUpdatedAt(it) }
            .sortedByDescending { it.updatedAt }
        _feed.value = merged
        if (merged.isEmpty() && !append) _emptyHint.value = "关注的内容暂无更新"
    }

    private fun fillUpdatedAt(item: ComicSummary): ComicSummary {
        if (item.updatedAt.isNotBlank()) {
            UpdatedAtCache.put(item.ref, item.updatedAt)
            return item
        }
        return UpdatedAtCache.of(item.ref)?.let { item.copy(updatedAt = it) } ?: item
    }
}

/** 关注信息流独立页（首页频道行「我的关注」入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowFeedScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: FollowFeedViewModel = viewModel(),
) {
    val feed by viewModel.feed.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val emptyHint by viewModel.emptyHint.collectAsState()
    val error by viewModel.error.collectAsState()
    val refreshTick by viewModel.refreshTick.collectAsState()
    val seenMarker by viewModel.seenMarker.collectAsState()
    val columns by GridSettings.columnsFlow.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        viewModel.ensureTargets()
        viewModel.initialRefresh()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        viewModel.markFeedSeen()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.markFeedSeen() }
    }

    val lastHandledTick = remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick != lastHandledTick.intValue) {
            lastHandledTick.intValue = refreshTick
            if (feed.isNotEmpty()) gridState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的关注") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                feed.isEmpty() && loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.fillMaxSize(),
                )

                feed.isEmpty() && emptyHint != null -> EmptyState(
                    message = emptyHint ?: "",
                    modifier = Modifier.fillMaxSize(),
                )

                feed.isEmpty() && error != null -> ErrorRetry(
                    message = error ?: "加载失败",
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                )

                feed.isEmpty() -> EmptyState(
                    message = "关注的内容暂无更新",
                    modifier = Modifier.fillMaxSize(),
                    actionText = "刷新",
                    onAction = viewModel::refresh,
                )

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        if (error != null) {
                            InlineErrorBanner(
                                message = error ?: "",
                                onRetry = viewModel::refresh,
                            )
                        }
                        ComicGridView(
                            comics = feed,
                            loading = loadingMore,
                            endReached = endReached,
                            listState = gridState,
                            onLoadMore = viewModel::loadMore,
                            onComicClick = onComicClick,
                            modifier = Modifier.weight(1f),
                            newUntil = seenMarker,
                            endHintText = "已加载全部关注内容",
                        )
                    }
                }
            }
        }
    }
}
