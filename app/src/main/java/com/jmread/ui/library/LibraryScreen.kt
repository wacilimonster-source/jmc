package com.jmread.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmRepository
import com.jmread.core.library.LibraryFilter
import com.jmread.core.library.LibraryItem
import com.jmread.core.library.LibraryRepository
import com.jmread.data.FavouriteSyncQueue
import com.jmread.data.GridSettings
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.common.ComicGridSkeleton
import com.jmread.ui.common.EmptyState
import com.jmread.ui.common.InlineErrorBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 统一书架 VM：四源并集（本地书架 ∪ 本地历史 ∪ 云端收藏 ∪ 云端历史），
 * 入口内不区分数据来自本地还是云端（docs/unified-library-design.md §5.2）。
 */
class LibraryViewModel : ViewModel() {

    val items = LibraryRepository.items

    val cloud = LibraryRepository.cloud

    /** 待同步的收藏变更条数（>0 显示提示行，点击立即 flush） */
    val pendingCount = FavouriteSyncQueue.pending.map { it.size }

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    val filtered = combine(items, _filter) { list, f -> list.filter { f.matches(it) } }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    init {
        // 进入书架：补拉云端首页 + flush 收藏同步队列（幂等，未登录自动跳过）
        refresh()
    }

    fun setFilter(f: LibraryFilter) {
        _filter.value = f
    }

    fun refresh() {
        viewModelScope.launch {
            LibraryRepository.flushPendingSync()
            LibraryRepository.refreshCloud()
        }
    }

    fun loadMore() {
        viewModelScope.launch { LibraryRepository.loadMoreCloud() }
    }

    /** 卡片心形：与详情页共用同一条双写路径 */
    fun toggleCollected(item: LibraryItem) {
        viewModelScope.launch {
            LibraryRepository.setCollected(
                comicId = item.comicId,
                target = !item.collected,
                title = item.title,
                author = item.author,
                coverUrl = item.coverUrl ?: "",
            )
        }
    }

    /** 清除阅读记录（历史 + 进度 + 读完标记）；收藏态不动 */
    fun clearReadHistory(comicId: String) {
        viewModelScope.launch { LibraryRepository.clearReadHistory(comicId) }
    }
}

/**
 * 书架页：统一收藏与阅读历史。
 * 点击卡片：有本地进度 → 续读直达阅读器；否则进详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit = {},
    onOpenReader: (String, Int) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel = viewModel(),
) {
    val items by viewModel.filtered.collectAsState()
    val allItems by viewModel.items.collectAsState()
    val cloud by viewModel.cloud.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState(initial = 0)
    val columns by GridSettings.columnsFlow.collectAsState()
    val listState = rememberLazyGridState()
    var menuTarget by remember { mutableStateOf<LibraryItem?>(null) }

    val itemsById = remember(allItems) { allItems.associateBy { it.comicId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 收藏同步积压提示行（未登录恒为 0，不占位）
            if (pendingCount > 0) {
                Text(
                    text = "$pendingCount 条收藏变更待同步 · 点击立即同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.refresh() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // 云端拉取失败：顶部错误条 + 本地内容照常可用（对齐首页断服模式）
            if (cloud.error != null) {
                InlineErrorBanner(
                    message = "云端数据同步失败，当前显示本地内容",
                    onRetry = viewModel::refresh,
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(f.label) },
                    )
                }
            }
            when {
                items.isEmpty() && cloud.loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.weight(1f),
                )
                items.isEmpty() -> EmptyState(
                    message = when (filter) {
                        LibraryFilter.ALL -> "书架还是空的\n看到喜欢的本子点亮 ♥，读过的会自动出现在这里"
                        LibraryFilter.COLLECTED -> "还没有收藏\n在详情页或卡片上点心形即可收藏"
                        LibraryFilter.READING -> "没有在读的作品"
                        LibraryFilter.FINISHED -> "还没有读完的作品"
                    },
                    modifier = Modifier.weight(1f),
                )
                else -> ComicGridView(
                    comics = items.map { it.toSummary() },
                    loading = cloud.loading,
                    endReached = cloud.endReached,
                    listState = listState,
                    onLoadMore = viewModel::loadMore,
                    onComicClick = { id ->
                        // 有本地进度 → 续读直达阅读器；云端-only / 纯收藏 → 详情页
                        val item = itemsById[id]
                        val order = item?.lastOrder
                        if (item != null && order != null && !item.finished) {
                            onOpenReader(id, order)
                        } else {
                            onOpenComic(id)
                        }
                    },
                    onComicLongClick = { id -> itemsById[id]?.let { menuTarget = it } },
                    modifier = Modifier.weight(1f),
                    statusLineOf = { id -> itemsById[id]?.statusLine },
                    collectedOf = { id -> itemsById[id]?.collected },
                    onFavouriteToggle = { id -> itemsById[id]?.let(viewModel::toggleCollected) },
                )
            }
        }
    }

    menuTarget?.let { target ->
        LibraryItemMenuDialog(
            item = target,
            loggedIn = JmRepository.isLoggedIn,
            onDismiss = { menuTarget = null },
            onToggleCollected = {
                viewModel.toggleCollected(target)
                menuTarget = null
            },
            onClearHistory = {
                viewModel.clearReadHistory(target.comicId)
                menuTarget = null
            },
            onOpenDetail = {
                onOpenComic(target.comicId)
                menuTarget = null
            },
        )
    }
}

@Composable
private fun LibraryItemMenuDialog(
    item: LibraryItem,
    loggedIn: Boolean,
    onDismiss: () -> Unit,
    onToggleCollected: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.title.ifBlank { "未知作品" },
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column {
                TextButton(onClick = onToggleCollected) {
                    Text(if (item.collected) "取消收藏${if (loggedIn) "（本地与云端）" else ""}" else "收藏")
                }
                TextButton(onClick = onClearHistory) {
                    Text("清除阅读记录（仅本机，收藏保留）")
                }
                TextButton(onClick = onOpenDetail) {
                    Text("打开详情")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
