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
import com.jmread.data.UpdatedAtCache
import com.jmread.ui.browse.ComicGridView
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 关注来源类型 */
private enum class FollowTargetType { AUTHOR, KEYWORD }

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

/** 关注流 VM：所有关注来源（收藏作者/关键词/分类标签）的最新作品聚合 */
class FollowFeedViewModel : ViewModel() {

    private val _feed = MutableStateFlow<List<ComicSummary>>(emptyList())
    val feed: StateFlow<List<ComicSummary>> = _feed.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _emptyHint = MutableStateFlow<String?>(null)
    val emptyHint: StateFlow<String?> = _emptyHint.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 刷新完成 +1（UI 据此回到列表顶部） */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    private var lastAutoRefreshAt = 0L
    private var everRefreshed = false

    /** 各来源已加载到的页数 / 是否到末页 */
    private var targetPages = mutableMapOf<String, Int>()
    private var targetEnded = mutableMapOf<String, Boolean>()
    private var targets: List<FollowTarget> = emptyList()
    private var loadingJob: kotlinx.coroutines.Job? = null

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

    /** 下拉刷新：所有来源重新从第 1 页拉取，取前 120 条 */
    fun refresh() {
        lastAutoRefreshAt = System.currentTimeMillis()
        rebuildTargets()
        if (targets.isEmpty()) {
            _feed.value = emptyList()
            _emptyHint.value = "还没有关注内容，去「我的 → 关注管理」添加作者或关键词关注"
            _error.value = null
            FollowFeedCache.clear()
            return
        }
        _emptyHint.value = null
        _loading.value = true
        _error.value = null
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            try {
                targetPages.clear()
                targetEnded.clear()
                val result = fetchTargetPage(1)
                val failed = result.failedCount
                if (result.items.isNotEmpty() || failed == 0) {
                    mergeIntoFeed(result.items, append = failed > 0)
                    if (failed == 0) _refreshTick.value++
                    if (_feed.value.size > 120) _feed.value = _feed.value.take(120)
                    runCatching { FollowFeedCache.save(_feed.value) }
                }
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

    private suspend fun fetchTargetPage(page: Int): FollowFetchResult {
        val result = mutableListOf<ComicSummary>()
        var failed = 0
        var requested = false
        val ordered = targets.sortedBy {
            if (it.type == FollowTargetType.KEYWORD && it.name.isNotBlank() && it.name.split(Regex("\\s+")).size > 1) 1 else 0
        }
        for (target in ordered.filter { targetEnded[it.key] != true }) {
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

    /** 组合关键词：每词分别全文搜索取 id 交集（"且"关系） */
    private suspend fun fetchKeywordPage(target: FollowTarget, startPage: Int): List<ComicSummary> {
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
        if (startPage > 1) {
            targetEnded[target.key] = true
            return emptyList()
        }
        val wordPageCounts: List<Pair<String, Int>> = coroutineScope {
            words.map { word ->
                async {
                    val first = com.jmread.core.runCatchingCancellable { searchWithRetry(word, 1, categories) }.getOrNull()
                    word to (first?.pages ?: 1).coerceIn(1, 25)
                }
            }.map { it.await() }
        }
        val wordSets: List<List<ComicSummary>> = wordPageCounts.map { (word, pages) ->
            (1..pages).mapNotNull { p ->
                com.jmread.core.runCatchingCancellable { searchWithRetry(word, p, categories) }.getOrNull()?.items
            }.flatten()
        }
        val wordIds = wordSets.map { set -> set.map { it.id }.toSet() }
        val common = wordIds[0].filter { id -> wordIds.all { it.contains(id) } }
        targetPages[target.key] = 25
        targetEnded[target.key] = true
        val firstSetById = wordSets[0].associateBy { it.id }
        return common.mapNotNull { firstSetById[it] }.sortedByComicSort(ComicSort.DD)
    }

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
    val emptyHint by viewModel.emptyHint.collectAsState()
    val error by viewModel.error.collectAsState()
    val refreshTick by viewModel.refreshTick.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        viewModel.ensureTargets()
        viewModel.initialRefresh()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume()
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
            if (feed.isEmpty() && emptyHint != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = emptyHint ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (error != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::refresh) { Text("重试") }
                        }
                    }
                    ComicGridView(
                        comics = feed,
                        loading = loading,
                        endReached = true,
                        listState = gridState,
                        onLoadMore = {},
                        onComicClick = onComicClick,
                        modifier = Modifier.weight(1f),
                        showTailLoading = false,
                    )
                }
            }
        }
    }
}
