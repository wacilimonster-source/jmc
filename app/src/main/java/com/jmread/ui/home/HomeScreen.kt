package com.jmread.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.model.BROWSE_TOTAL_SENTINEL
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.RankTab
import com.jmread.core.model.sortedByComicSort
import com.jmread.ui.browse.ComicGridItem
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.ReadFilter
import com.jmread.ui.browse.filterByRead
import com.jmread.ui.browse.readFilterOptions
import com.jmread.ui.common.ComicGridSkeleton
import com.jmread.ui.common.EmptyState
import com.jmread.ui.common.ErrorRetry
import com.jmread.ui.common.InlineErrorBanner
import com.jmread.ui.follow.FollowFeedViewModel
import kotlinx.coroutines.delay

/**
 * 首页 = 三 Tab（关注 / 排行榜 / 随便看看）。
 *
 * 排行榜保留榜单档位；原先榜单上方的频道入口 chips（推荐本本/汉化组/去码/全彩化/每周必看）
 * 改为在榜单下方直接展开成分区网格，内容与排行榜同款卡片，不再逐页跳转。
 * 关注流是首屏 Tab，支持分页与新内容基线；
 * 随便看看因源无 /random，使用月榜热池本地随机，不伪装成服务端随机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
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
    val rankCacheAt by viewModel.rankCacheAt.collectAsState()
    val rankFirstContentReady by viewModel.rankFirstContentReady.collectAsState()
    val promoteSections by viewModel.promoteSections.collectAsState()
    val weeklySection by viewModel.weeklySection.collectAsState()
    val sectionsLoading by viewModel.sectionsLoading.collectAsState()
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
    // 三个 Tab 是条件分支渲染：没有 SaveableStateHolder 时，切走再回来
    // RankTab 内部的 rememberSaveable（分区/排序/筛选）会被丢弃。
    val tabStateHolder = rememberSaveableStateHolder()
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

    LaunchedEffect(Unit) {
        com.jmread.core.update.UpdateState.checkOnce()
        viewModel.loadRank(rankType)
        followViewModel.ensureTargets()
        // 首屏是排行榜；关注流延后启动，避免两条请求链同帧撞车
        if (selectedTab != 0) delay(900)
        followViewModel.initialRefresh()
    }

    // PERF-05：榜单内容可见后才做档位探测与分区加载，避免与首屏抢请求/图片预算
    LaunchedEffect(rankFirstContentReady) {
        if (rankFirstContentReady) viewModel.onRankContentVisible()
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
                0 -> tabStateHolder.SaveableStateProvider("follow") {
                    FollowTab(
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
                }

                1 -> tabStateHolder.SaveableStateProvider("rank") {
                    RankTab(
                        comics = rankComics,
                        rankType = rankType,
                        rankTabs = rankTabs,
                        loading = rankLoading,
                        error = rankError,
                        total = rankTotal,
                        cacheAt = rankCacheAt,
                        columns = gridColumns,
                        promoteSections = promoteSections,
                        weeklySection = weeklySection,
                        sectionsLoading = sectionsLoading,
                        rankRefreshTick = rankRefreshTick,
                        onTypeChange = { viewModel.loadRank(it) },
                        onRefreshRank = { viewModel.loadRank(rankType, force = true) },
                        onRefreshSections = viewModel::refreshSections,
                        onOpenWeekly = onOpenWeekly,
                        onComicClick = onComicClick,
                        gridState = rankGridState,
                    )
                }

                else -> tabStateHolder.SaveableStateProvider("random") {
                    RandomTab(
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

/** 首页内容源：榜单档位 / 推荐分区 / 每周必看，三者同级切换 */
private const val SOURCE_RANK = "rank"
private const val SOURCE_WEEKLY = "weekly"
private fun sectionSource(key: String) = "section:$key"

/** 分区可用的排序：/promote block 只带 update_at，没有点赞/观看数 */
private val sectionSorts = listOf(ComicSort.DD, ComicSort.DA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankTab(
    comics: List<ComicSummary>,
    rankType: String,
    rankTabs: List<RankTab>,
    loading: Boolean,
    error: String?,
    total: Int,
    cacheAt: Long?,
    columns: Int,
    promoteSections: List<HomePromoteSection>,
    weeklySection: HomeWeeklySection?,
    sectionsLoading: Boolean,
    rankRefreshTick: Int,
    onTypeChange: (String) -> Unit,
    onRefreshRank: () -> Unit,
    onRefreshSections: () -> Unit,
    onOpenWeekly: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: LazyGridState,
) {
    var source by rememberSaveable { mutableStateOf(SOURCE_RANK) }
    var sectionSortName by rememberSaveable { mutableStateOf(ComicSort.DD.name) }
    var sectionFilterName by rememberSaveable { mutableStateOf(ReadFilter.ALL.name) }
    val sectionSort = ComicSort.valueOf(sectionSortName)
    val sectionFilter = ReadFilter.valueOf(sectionFilterName)

    val selectedSection = promoteSections.firstOrNull { source == sectionSource(it.key) }
    val weeklySelected = source == SOURCE_WEEKLY
    val rankSelected = !weeklySelected && selectedSection == null

    // 榜单静默刷新成功才回顶；用户正在看分区/每周必看时不能被后台刷新打断
    val lastRankTick = remember { mutableIntStateOf(rankRefreshTick) }
    LaunchedEffect(rankRefreshTick) {
        if (rankRefreshTick != lastRankTick.intValue) {
            lastRankTick.intValue = rankRefreshTick
            if (rankSelected && comics.isNotEmpty()) gridState.scrollToItem(0)
        }
    }

    // 分区轮换后原选中项消失：回到榜单，避免内容区空白
    LaunchedEffect(promoteSections, source) {
        if (source.startsWith("section:") && promoteSections.isNotEmpty() &&
            promoteSections.none { source == sectionSource(it.key) }
        ) {
            source = SOURCE_RANK
        }
    }

    // 切换内容源时回到顶部（首次进入不强制，保留榜单滚动位置恢复）
    var lastSource by remember { mutableStateOf(source) }
    LaunchedEffect(source) {
        if (source != lastSource) {
            lastSource = source
            gridState.requestScrollToItem(0)
        }
    }

    val sectionComics = remember(selectedSection, sectionSort, sectionFilter) {
        selectedSection?.comics
            ?.sortedByComicSort(sectionSort)
            ?.filterByRead(sectionFilter)
            .orEmpty()
    }
    val weeklyComics = weeklySection?.comics.orEmpty()

    val refreshing = if (rankSelected) loading else sectionsLoading
    val onRefresh = if (rankSelected) onRefreshRank else onRefreshSections

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 榜单档位与推荐分区同级：同一排 chips，选中谁就展示谁
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rankTabs.forEach { tab ->
                    FilterChip(
                        selected = rankSelected && rankType == tab.value,
                        onClick = {
                            source = SOURCE_RANK
                            onTypeChange(tab.value)
                        },
                        label = { Text(tab.label) },
                    )
                }
                if (promoteSections.isNotEmpty() || weeklySection != null) {
                    Box(
                        Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    )
                }
                promoteSections.forEach { section ->
                    val key = sectionSource(section.key)
                    FilterChip(
                        selected = source == key,
                        onClick = { source = key },
                        label = { Text(section.title) },
                    )
                }
                if (weeklySection != null) {
                    FilterChip(
                        selected = weeklySelected,
                        onClick = { source = SOURCE_WEEKLY },
                        label = { Text("每周必看") },
                    )
                }
            }

            // 分区选中时补排序与阅读状态筛选；榜单档位本身即筛选，不重复展示
            if (selectedSection != null) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sectionSorts, key = { "sort-${it.name}" }) { s ->
                        FilterChip(
                            selected = sectionSort == s,
                            onClick = {
                                sectionSortName = s.name
                                gridState.requestScrollToItem(0)
                            },
                            label = { Text(s.label) },
                        )
                    }
                    items(readFilterOptions, key = { "read-${it.first.name}" }) { (f, label) ->
                        FilterChip(
                            selected = sectionFilter == f,
                            onClick = {
                                sectionFilterName = f.name
                                gridState.requestScrollToItem(0)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (weeklySelected && weeklySection != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 2.dp),
                ) {
                    Text(
                        text = "每周必看 · ${weeklySection.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenWeekly) { Text("切换期数") }
                }
            }

            val count = when {
                rankSelected ->
                    if (comics.isNotEmpty() && total > 0 && total != BROWSE_TOTAL_SENTINEL) total else null
                selectedSection != null -> selectedSection.comics.size
                else -> weeklyComics.size
            }
            if (count != null && count > 0) {
                Text(
                    text = "共 $count 部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (rankSelected && cacheAt != null) {
                InlineErrorBanner(
                    message = "服务端异常 · 显示 ${rankCacheAgeText(cacheAt)}前的缓存",
                    onRetry = onRefreshRank,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    selectedSection != null -> {
                        if (sectionComics.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "section-empty") {
                                EmptyState(
                                    message = "没有符合筛选条件的作品",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                    actionText = "看全部",
                                    onAction = {
                                        sectionFilterName = ReadFilter.ALL.name
                                        gridState.requestScrollToItem(0)
                                    },
                                )
                            }
                        } else {
                            items(
                                sectionComics,
                                key = { "section-${selectedSection.key}-${it.ref}" },
                            ) { comic ->
                                ComicGridItem(
                                    comic = comic,
                                    columns = columns,
                                    onComicClick = onComicClick,
                                )
                            }
                        }
                    }

                    weeklySelected -> {
                        if (weeklyComics.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "weekly-empty") {
                                EmptyState(
                                    message = "每周必看暂无内容",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                )
                            }
                        } else {
                            items(weeklyComics, key = { "weekly-${it.ref}" }) { comic ->
                                ComicGridItem(
                                    comic = comic,
                                    columns = columns,
                                    onComicClick = onComicClick,
                                )
                            }
                        }
                    }

                    else -> when {
                        comics.isEmpty() && error != null && !loading -> item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "rank-error",
                        ) {
                            ErrorRetry(
                                message = error,
                                onRetry = onRefreshRank,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                            )
                        }

                        comics.isEmpty() && loading -> items(
                            count = columns * 3,
                            key = { "rank-skeleton-$it" },
                        ) {
                            RankSkeletonCell()
                        }

                        comics.isEmpty() -> item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "rank-empty",
                        ) {
                            EmptyState(
                                message = "该档位暂无数据\n可切换上方其它档位",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                            )
                        }

                        else -> {
                            if (error != null) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "rank-inline-error") {
                                    InlineErrorBanner(message = error, onRetry = onRefreshRank)
                                }
                            }
                            items(comics, key = { "rank-${it.ref}" }) { comic ->
                                ComicGridItem(
                                    comic = comic,
                                    columns = columns,
                                    onComicClick = onComicClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 榜单首屏骨架格：与 ComicGridView 的卡片同比例，避免加载完成后跳动 */
@Composable
private fun RankSkeletonCell() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(6.dp),
            ),
    )
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

/** 缓存新鲜度文案（P1-07）：刚刚 / N 分钟前 / N 小时前 / N 天前 */
private fun rankCacheAgeText(at: Long): String {
    val delta = (System.currentTimeMillis() - at).coerceAtLeast(0L)
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        else -> "${minutes / (60 * 24)}天前"
    }
}
