package com.jmread.ui.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicStatus
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.PaginationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorComicsScreen(
    author: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: AuthorViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val status by viewModel.status.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val listState = rememberLazyGridState()
    val supportedSorts = remember { JmCapabilities.supportedSorts }

    // 保存滚动位置（每次 Activity 暂停时都保存，覆盖所有导航场景）
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        viewModel.saveScrollState(listState.firstVisibleItemIndex, currentPage)
    }
    // 恢复滚动状态（导航返回后首次 recompose 时执行）：
    // 恢复走专用通道只回填页码，绝不能复用 loadComics——否则切后台回来后的
    // 第一次排序/筛选点击会被恢复分支拦截成"只回填不加载"
    LaunchedEffect(Unit) {
        val restorePage = viewModel.consumeScrollRestorePage()
        if (restorePage == null) {
            viewModel.loadComics(author, page = 1)
        }
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
        }
    }

    LaunchedEffect(supportedSorts) {
        // 空列表兜底：supportedSorts 由源声明，异常返回空时会越界崩溃
        supportedSorts.firstOrNull()?.let { def ->
            if (viewModel.sort.value !in supportedSorts) viewModel.setSort(def)
        }
    }

    // 组合期不读盘：初值 false，LaunchedEffect 中查询
    var favourited by remember { mutableStateOf(false) }
    LaunchedEffect(author) {
        favourited = com.jmread.data.AuthorFavourites.contains(author)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (favourited) {
                            com.jmread.data.AuthorFavourites.remove(author)
                        } else {
                            com.jmread.data.AuthorFavourites.add(
                                author = author,
                                coverUrl = comics.firstOrNull()?.coverUrl.orEmpty(),
                            )
                        }
                        favourited = !favourited
                    }) {
                        Icon(
                            imageVector = if (favourited) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (favourited) "取消收藏作者" else "收藏作者",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(supportedSorts, key = { it.name }) { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { viewModel.setSort(s) },
                        label = { Text(s.label) },
                    )
                }
            }
            // 本源无完结字段：状态筛选行不渲染（能力说真话）
            if (JmCapabilities.supportsStatusFilter) {
                LazyRow(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ComicStatus.entries.toList(), key = { it.name }) { st ->
                        FilterChip(
                            selected = status == st,
                            onClick = { viewModel.setStatus(st) },
                            label = { Text(st.label) },
                        )
                    }
                }
            }
            if (error != null && comics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = error ?: "加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (comics.isEmpty() && !loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "该作者暂无作品",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
                PaginationBar(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPageChange = { p ->
                        viewModel.jumpToPage(p)
                        // 服务端换页：回顶展示新页（返回定位不受影响，恢复只在导航返回重组时执行）
                        listState.requestScrollToItem(0)
                    },
                )
            }
        }
    }
}
