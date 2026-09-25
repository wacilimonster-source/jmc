package com.jmread.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmCapabilities
import com.jmread.core.model.RankTab
import com.jmread.core.model.BROWSE_TOTAL_SENTINEL
import com.jmread.ui.browse.ComicGridView

/**
 * 首页 = 排行榜主内容（2026-09-25 设计变更，对齐官方 App）。
 *
 * 结构：标题行 + 频道入口 chips（推荐本本/汉化组/禁漫去码/全彩化/每周必看/我的关注）
 *       + 榜单档位 chips（新晋热榜/总排行/月榜/周榜，档位可用性由探测决定）
 *       + 排行网格（主内容，下拉刷新）。
 * 原三 Tab 结构（关注/排行榜/每周必看）取消，关注流与每周必看进入独立页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    onOpenPromote: () -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    onOpenWeekly: () -> Unit = {},
    onOpenFollow: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val rankComics by viewModel.rankComics.collectAsState()
    val rankType by viewModel.rankType.collectAsState()
    val rankTabs by viewModel.rankTabs.collectAsState()
    val rankLoading by viewModel.rankLoading.collectAsState()
    val rankError by viewModel.rankError.collectAsState()
    val rankRefreshTick by viewModel.rankRefreshTick.collectAsState()
    val rankTotal by viewModel.rankTotal.collectAsState()
    val updateInfo by com.jmread.core.update.UpdateState.updateInfo.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val scrollStateRestored by viewModel.isScrollStateRestored.collectAsState()

    // 保存/恢复排行榜网格滚动位置
    DisposableEffect(Unit) {
        onDispose { viewModel.saveScrollState(gridState.firstVisibleItemIndex) }
    }
    LaunchedEffect(scrollStateRestored) {
        if (viewModel.savedRankIndex > 0) {
            gridState.scrollToItem(viewModel.savedRankIndex)
            viewModel.markScrollStateRestored()
        }
    }

    LaunchedEffect(Unit) {
        com.jmread.core.update.UpdateState.checkOnce()
        // 榜单档位可用性探测：异步，不阻塞首屏（初值是乐观全集）
        viewModel.ensureRankTabs()
        viewModel.loadRank(rankType)
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume()
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
                // 频道入口行（官方 App 内容位）
                if (JmCapabilities.hasPromote || JmCapabilities.hasChannels ||
                    JmCapabilities.hasWeeklyPicks
                ) {
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
                        FilterChip(
                            selected = false,
                            onClick = onOpenFollow,
                            label = { Text("我的关注") },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = rankLoading,
            onRefresh = { viewModel.loadRank(rankType, force = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 档位 chips
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
                            onClick = { viewModel.loadRank(tab.value) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                if (rankComics.isNotEmpty() && rankTotal > 0 && rankTotal != BROWSE_TOTAL_SENTINEL) {
                    Text(
                        text = "共 $rankTotal 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                val err = rankError
                if (err != null && rankComics.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.loadRank(rankType, force = true) }) {
                            Text("重试")
                        }
                    }
                }
                when {
                    rankComics.isEmpty() && err != null && !rankLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                                TextButton(onClick = { viewModel.loadRank(rankType, force = true) }) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                    rankComics.isEmpty() && rankLoading -> {
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
                            loading = rankLoading,
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

/** 频道入口 chips（数据来自 JmRepository.channels，UI 按 id 排回调） */
@Composable
private fun JmRepositoryChannelEntries(onOpen: (String) -> Unit) {
    // 直接引用常量列表：频道集合编译期固定，不引入 VM 依赖
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
