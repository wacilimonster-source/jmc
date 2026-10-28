package com.jmread.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmCapabilities
import com.jmread.core.model.BROWSE_TOTAL_SENTINEL
import com.jmread.core.model.RankTab
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.common.ComicGridSkeleton
import com.jmread.ui.common.EmptyState
import com.jmread.ui.common.ErrorRetry
import com.jmread.ui.common.InlineErrorBanner
import com.jmread.ui.follow.FollowFeedViewModel
import kotlinx.coroutines.delay

/**
 * 首页 = 三 Tab（关注 / 排行榜 / 随便看看）。
 *
 * 排行榜保留官方频道入口（推荐本本/汉化组/去码/全彩化/每周必看）与榜单档位；
 * 关注流不再单独占一个频道 chip，而是首屏 Tab，支持分页与新内容基线；
 * 随便看看因源无 /random，使用月榜热池本地随机，不伪装成服务端随机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    onOpenPromote: () -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    onOpenWeekly: () -> Unit = {},
    onOpenFollowManage: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
    followViewModel: FollowFeedViewModel = viewModel(),
) {
    val rankComics by viewModel.rankComics.collectAsState()
    val rankType by viewModel.rankType.collectAsState()
    val rankTabs by viewModel.rankTabs.collectAsState()
    val rankLoading by viewModel.rankLoading.collectAsState()
    val rankError by viewModel.rankError.collectAsState()
    val rankRefreshTick by viewModel.rankRefreshTick.collectAsState()
    val rankTotal by viewModel.rankTotal.collectAsState()
    val randomComics by viewModel.randomComics.collectAsState()
    val randomLoading by viewModel.randomLoading.collectAsState()
    val randomError by viewModel.randomError.collectAsState()
    val followFeed by followViewModel.feed.collectAsState()
    val followLoading by followViewModel.loading.collectAsState()
    val followLoadingMore by followViewModel.loadingMore.collectAsState()
    val followEndReached by followViewModel.endReached.collectAsState()
    val followEmptyHint by followViewModel.emptyHint.collectAsState()
    val followError by followViewModel.error.collectAsState()
    val followRefreshTick by followViewModel.refreshTick.collectAsState()
    val seenMarker by followViewModel.seenMarker.collectAsState()
    val updateInfo by com.jmread.core.update.UpdateState.updateInfo.collectAsState()
    val gridColumns by com.jmread.data.GridSettings.columnsFlow.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(1) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val hasNewFollow = seenMarker.isNotBlank() && followFeed.any {
        it.updatedAt.isNotBlank() && it.updatedAt > seenMarker
    }
    var followTabActive by remember { mutableStateOf(false) }
    val latestTab = rememberUpdatedState(selectedTab)

    val followGridState = rememberLazyGridState()
    val rankGridState = rememberLazyGridState()
    val randomGridState = rememberLazyGridState()
    val scrollStateRestored by viewModel.isScrollStateRestored.collectAsState()

    // 离开关注 Tab（切走/退出首页）时推进「已看到」基线
    LaunchedEffect(selectedTab) {
        if (followTabActive && selectedTab != 0) followViewModel.markFeedSeen()
        followTabActive = selectedTab == 0
        if (selectedTab == 0) {
            followViewModel.ensureTargets()
            followViewModel.initialRefresh()
        }
        if (selectedTab == 2) viewModel.ensureRandomLoaded()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (latestTab.value == 0) followViewModel.markFeedSeen()
            viewModel.saveScrollState(rankGridState.firstVisibleItemIndex)
        }
    }

    // 排行榜滚动位置恢复（原有行为）
    LaunchedEffect(scrollStateRestored) {
        if (viewModel.savedRankIndex > 0 && selectedTab == 1) {
            rankGridState.scrollToItem(viewModel.savedRankIndex)
            viewModel.markScrollStateRestored()
        }
    }

    // 刷新完成后回到顶部；以组合时的 tick 为基线，从详情返回不强制回顶
    val lastFollowTick = remember { mutableIntStateOf(followRefreshTick) }
    LaunchedEffect(followRefreshTick) {
        if (followRefreshTick != lastFollowTick.intValue) {
            lastFollowTick.intValue = followRefreshTick
            if (followFeed.isNotEmpty()) followGridState.scrollToItem(0)
        }
    }
    val lastRankTick = remember { mutableIntStateOf(rankRefreshTick) }
    LaunchedEffect(rankRefreshTick) {
        if (rankRefreshTick != lastRankTick.intValue) {
            lastRankTick.intValue = rankRefreshTick
            if (rankComics.isNotEmpty()) rankGridState.scrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        com.jmread.core.update.UpdateState.checkOnce()
        viewModel.ensureRankTabs()
        viewModel.loadRank(rankType)
        followViewModel.ensureTargets()
        // 首屏是排行榜；关注流延后启动，避免两条请求链同帧撞车
        if (selectedTab != 0) delay(900)
        followViewModel.initialRefresh()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume()
        followViewModel.refreshOnResume()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (latestTab.value == 0) followViewModel.markFeedSeen()
    }

    if (updateInfo != null && showUpdateDialog) {
        com.jmread.ui.update.UpdateDialog(
            info = updateInfo!!,
            onDismiss = {
                showUpdateDialog = false
                com.jmread.core.update.UpdateState.dismiss()
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                ) {
                    Text(
                        text = "JM Reader",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 更新横幅：点主体打开对话框，「稍后再说」只静默当前版本
                if (updateInfo != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showUpdateDialog = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "发现新版本 v${updateInfo!!.version}，点击更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "稍后再说",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { com.jmread.core.update.UpdateState.dismiss() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("关注")
                                if (hasNewFollow) {
                                    Box(
                                        Modifier
                                            .padding(start = 4.dp)
                                            .size(7.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape,
                                            ),
                                    )
                                }
                            }
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("排行榜") },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("随便看看") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> FollowTab(
                    comics = followFeed,
                    loading = followLoading,
                    loadingMore = followLoadingMore,
                    endReached = followEndReached,
                    emptyHint = followEmptyHint,
                    error = followError,
                    newUntil = seenMarker,
                    columns = gridColumns,
                    onLoadMore = followViewModel::loadMore,
                    onRefresh = followViewModel::refresh,
                    onManage = onOpenFollowManage,
                    onComicClick = onComicClick,
                    gridState = followGridState,
                )

                1 -> RankTab(
                    comics = rankComics,
                    rankType = rankType,
                    rankTabs = rankTabs,
                    loading = rankLoading,
                    error = rankError,
                    total = rankTotal,
                    columns = gridColumns,
                    onTypeChange = { viewModel.loadRank(it) },
                    onRefresh = { viewModel.loadRank(rankType, force = true) },
                    onOpenPromote = onOpenPromote,
                    onOpenChannel = onOpenChannel,
                    onOpenWeekly = onOpenWeekly,
                    onComicClick = onComicClick,
                    gridState = rankGridState,
                )

                else -> RandomTab(
                    comics = randomComics,
                    loading = randomLoading,
                    error = randomError,
                    columns = gridColumns,
                    onRefresh = viewModel::refreshRandom,
                    onComicClick = onComicClick,
                    gridState = randomGridState,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowTab(
    comics: List<com.jmread.core.model.ComicSummary>,
    loading: Boolean,
    loadingMore: Boolean,
    endReached: Boolean,
    emptyHint: String?,
    error: String?,
    newUntil: String,
    columns: Int,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onManage: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: LazyGridState,
) {
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            val newCount = if (newUntil.isBlank()) 0 else comics.count {
                it.updatedAt.isNotBlank() && it.updatedAt > newUntil
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp),
            ) {
                Text(
                    text = "我的关注",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (newCount > 0) {
                    Text(
                        text = "· $newCount 条新内容",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onManage) { Text("管理") }
            }
            when {
                comics.isEmpty() && loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() && emptyHint != null -> EmptyState(
                    message = emptyHint,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() && error != null -> ErrorRetry(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() -> EmptyState(
                    message = "关注的内容暂无更新",
                    modifier = Modifier.weight(1f),
                    actionText = "刷新",
                    onAction = onRefresh,
                )

                else -> {
                    if (error != null) {
                        InlineErrorBanner(message = error, onRetry = onRefresh)
                    }
                    ComicGridView(
                        comics = comics,
                        loading = loadingMore,
                        endReached = endReached,
                        listState = gridState,
                        onLoadMore = onLoadMore,
                        onComicClick = onComicClick,
                        newUntil = newUntil,
                        endHintText = "已加载全部关注内容",
                        modifier = Modifier.weight(1f),
                        showTailLoading = true,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankTab(
    comics: List<com.jmread.core.model.ComicSummary>,
    rankType: String,
    rankTabs: List<RankTab>,
    loading: Boolean,
    error: String?,
    total: Int,
    columns: Int,
    onTypeChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenPromote: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenWeekly: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: LazyGridState,
) {
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (JmCapabilities.hasPromote || JmCapabilities.hasChannels || JmCapabilities.hasWeeklyPicks) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (JmCapabilities.hasPromote) {
                        FilterChip(
                            selected = false,
                            onClick = onOpenPromote,
                            label = { Text("🔥 推荐本本") },
                        )
                    }
                    if (JmCapabilities.hasChannels) {
                        JmRepositoryChannelEntries { channelId -> onOpenChannel(channelId) }
                    }
                    if (JmCapabilities.hasWeeklyPicks) {
                        FilterChip(
                            selected = false,
                            onClick = onOpenWeekly,
                            label = { Text("每周必看") },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rankTabs.forEach { tab ->
                    FilterChip(
                        selected = rankType == tab.value,
                        onClick = { onTypeChange(tab.value) },
                        label = { Text(tab.label) },
                    )
                }
            }
            if (comics.isNotEmpty() && total > 0 && total != BROWSE_TOTAL_SENTINEL) {
                Text(
                    text = "共 $total 部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                comics.isEmpty() && error != null && !loading -> ErrorRetry(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() && loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() -> EmptyState(
                    message = "该档位暂无数据\n可切换上方其它档位",
                    modifier = Modifier.weight(1f),
                )

                else -> {
                    if (error != null) {
                        InlineErrorBanner(message = error, onRetry = onRefresh)
                    }
                    ComicGridView(
                        comics = comics,
                        loading = false,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RandomTab(
    comics: List<com.jmread.core.model.ComicSummary>,
    loading: Boolean,
    error: String?,
    columns: Int,
    onRefresh: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: LazyGridState,
) {
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp),
            ) {
                Text(
                    text = "从近期热门中随机挑选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) { Text("换一批") }
            }
            when {
                comics.isEmpty() && loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() && error != null -> ErrorRetry(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.weight(1f),
                )

                comics.isEmpty() -> EmptyState(
                    message = "暂无内容，稍后再试",
                    modifier = Modifier.weight(1f),
                    actionText = "换一批",
                    onAction = onRefresh,
                )

                else -> {
                    if (error != null) {
                        InlineErrorBanner(message = error, onRetry = onRefresh)
                    }
                    ComicGridView(
                        comics = comics,
                        loading = false,
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

/** 频道入口 chips（数据来自 JmRepository.channels，UI 按 id 排回调） */
@Composable
private fun JmRepositoryChannelEntries(onOpen: (String) -> Unit) {
    listOf(
        "hangroup" to "禁漫汉化组",
        "nomosaic" to "禁漫去码",
        "coloring" to "全彩化",
    ).forEach { (id, label) ->
        FilterChip(
            selected = false,
            onClick = { onOpen(id) },
            label = { Text(label) },
        )
    }
}
