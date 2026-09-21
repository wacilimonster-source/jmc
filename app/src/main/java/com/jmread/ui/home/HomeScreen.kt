package com.jmread.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.ui.browse.ComicGridView

/**
 * 首页：顶部标签「关注 / 排行榜 / 每周必看」。
 * 排行榜（默认，index=1）：档位由 ViewModel 探测后给出（见 RankTab），total 为真实命中数。
 * 关注：关注信息流（作者/关键词/分类标签最新更新的聚合网格）。
 * 每周必看：期数切换 + 该期内容流（/week，禁漫独有内容位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val followFeed by viewModel.followFeed.collectAsState()
    val followLoading by viewModel.followLoading.collectAsState()
    val followEmptyHint by viewModel.followEmptyHint.collectAsState()
    val followError by viewModel.followError.collectAsState()
    val rankComics by viewModel.rankComics.collectAsState()
    val rankType by viewModel.rankType.collectAsState()
    val rankTabs by viewModel.rankTabs.collectAsState()
    val rankLoading by viewModel.rankLoading.collectAsState()
    val rankError by viewModel.rankError.collectAsState()
    val rankRefreshTick by viewModel.rankRefreshTick.collectAsState()
    val rankTotal by viewModel.rankTotal.collectAsState()
    val updateInfo by com.jmread.core.update.UpdateState.updateInfo.collectAsState()
    val followRefreshTick by viewModel.refreshTick.collectAsState()
    val weekPeriods by viewModel.weekPeriods.collectAsState()
    val weekSelected by viewModel.weekSelected.collectAsState()
    val weekComics by viewModel.weekComics.collectAsState()
    val weekLoading by viewModel.weekLoading.collectAsState()
    val weekError by viewModel.weekError.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(1) }

    val followGridState = rememberLazyGridState()
    val rankGridState = rememberLazyGridState()
    val weekGridState = rememberLazyGridState()
    val scrollStateRestored by viewModel.isScrollStateRestored.collectAsState()

    // 保存当前 Tab 的滚动位置（导航离开时）
    DisposableEffect(Unit) {
        onDispose {
            val index = when (selectedTab) {
                0 -> followGridState.firstVisibleItemIndex
                1 -> rankGridState.firstVisibleItemIndex
                else -> weekGridState.firstVisibleItemIndex
            }
            viewModel.saveScrollState(selectedTab, index)
        }
    }
    // 恢复滚动位置
    LaunchedEffect(scrollStateRestored) {
        val index = when (selectedTab) {
            0 -> viewModel.savedFollowIndex
            1 -> viewModel.savedRankIndex
            else -> viewModel.savedWeekIndex
        }
        if (index > 0) {
            when (selectedTab) {
                0 -> followGridState.scrollToItem(index)
                1 -> rankGridState.scrollToItem(index)
                else -> weekGridState.scrollToItem(index)
            }
            viewModel.markScrollStateRestored()
        }
    }

    LaunchedEffect(Unit) {
        com.jmread.core.update.UpdateState.checkOnce()
        viewModel.ensureFollowTargets()
        // 榜单档位可用性探测：异步，不阻塞首屏（初值是乐观全集）
        viewModel.ensureRankTabs()
        // 首屏默认落在排行榜（selectedTab=1），它是唯一可见内容，立即开跑；
        // 关注流稍后启动（后台，延迟对用户不可感知）
        if (selectedTab == 1) kotlinx.coroutines.delay(1_200)
        viewModel.initialRefresh()
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && rankComics.isEmpty() && rankError == null) {
            viewModel.loadRank(rankType)
        } else if (selectedTab == 2) {
            viewModel.ensureWeekLoaded()
        }
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
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                ) {
                    Text(
                        text = "JM Reader",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 更新横幅
                if (updateInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showUpdateDialog = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "发现新版本 v${updateInfo!!.version}，点击更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("关注") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("排行榜") },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("每周必看") },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> FollowTab(
                comics = followFeed,
                loading = followLoading,
                endReached = true,
                emptyHint = followEmptyHint,
                error = followError,
                refreshTick = followRefreshTick,
                onLoadMore = {},
                onRefresh = viewModel::refresh,
                onComicClick = onComicClick,
                gridState = followGridState,
                modifier = Modifier.padding(innerPadding),
            )
            1 -> RankTab(
                rankComics = rankComics,
                rankType = rankType,
                rankTabs = rankTabs,
                rankTotal = rankTotal,
                loading = rankLoading,
                error = rankError,
                refreshTick = rankRefreshTick,
                onTypeChange = { viewModel.loadRank(it) },
                onRefresh = { viewModel.loadRank(rankType, force = true) },
                onComicClick = onComicClick,
                gridState = rankGridState,
                modifier = Modifier.padding(innerPadding),
            )
            else -> WeekTab(
                periods = weekPeriods,
                selected = weekSelected,
                comics = weekComics,
                loading = weekLoading,
                error = weekError,
                onPeriodChange = { viewModel.selectWeek(it) },
                onComicClick = onComicClick,
                gridState = weekGridState,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** 关注信息流：聚合网格（按更新时间由近至远） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowTab(
    comics: List<com.jmread.core.model.ComicSummary>,
    loading: Boolean,
    endReached: Boolean,
    emptyHint: String?,
    error: String? = null,
    refreshTick: Int,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    val lastHandledTick = remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick != lastHandledTick.intValue) {
            lastHandledTick.intValue = refreshTick
            if (comics.isNotEmpty()) {
                gridState.scrollToItem(0)
            }
        }
    }
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (comics.isEmpty() && emptyHint != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyHint,
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
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh) {
                            Text("重试")
                        }
                    }
                }
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = endReached,
                    listState = gridState,
                    onLoadMore = onLoadMore,
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                    showTailLoading = false,
                )
            }
        }
    }
}

/** 排行榜：日/周/月切换 + 网格 + 真实命中数 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankTab(
    rankComics: List<com.jmread.core.model.ComicSummary>,
    rankType: String,
    rankTabs: List<com.jmread.core.model.RankTab>,
    rankTotal: Int,
    loading: Boolean,
    error: String?,
    refreshTick: Int,
    onTypeChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    val lastHandledTick = remember { mutableIntStateOf(refreshTick) }
    LaunchedEffect(refreshTick) {
        if (refreshTick != lastHandledTick.intValue) {
            lastHandledTick.intValue = refreshTick
            if (rankComics.isNotEmpty()) {
                gridState.scrollToItem(0)
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (rankComics.isNotEmpty() && rankTotal > 0) {
                    Text(
                        text = "共 $rankTotal 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                if (error != null && rankComics.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onTypeChange(rankType) }) {
                            Text("重试")
                        }
                    }
                }
                when {
                    rankComics.isEmpty() && error != null && !loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                                TextButton(onClick = { onTypeChange(rankType) }) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                    rankComics.isEmpty() && loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    rankComics.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "该档位暂无数据",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "可切换上方其它档位",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    else -> {
                        ComicGridView(
                            comics = rankComics,
                            loading = loading,
                            endReached = true,
                            listState = gridState,
                            onLoadMore = {},
                            onComicClick = onComicClick,
                        )
                    }
                }
            }
        }
    }
}

/** 每周必看：期数横向切换 + 该期内容流 */
@Composable
private fun WeekTab(
    periods: List<com.jmread.core.model.WeekPeriod>,
    selected: com.jmread.core.model.WeekPeriod?,
    comics: List<com.jmread.core.model.ComicSummary>,
    loading: Boolean,
    error: String?,
    onPeriodChange: (com.jmread.core.model.WeekPeriod) -> Unit,
    onComicClick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(periods, key = { it.id }) { p ->
                FilterChip(
                    selected = p.id == selected?.id,
                    onClick = { onPeriodChange(p) },
                    label = { Text(p.label.ifBlank { "第 ${p.id} 期" }) },
                )
            }
        }
        when {
            loading && comics.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            error != null && comics.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            comics.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "选择上方期数查看内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = true,
                    listState = gridState,
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    showTailLoading = false,
                )
            }
        }
    }
}
