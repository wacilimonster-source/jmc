package com.jmread.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.model.ComicSort
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.filterByRead

/** 搜索页：输入框（含返回/重置/标签筛选）+ 排序筛选 + 结果网格 + 底部页码分页 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: (() -> Unit)? = null,
    onComicClick: (String) -> Unit = {},
    initialKeyword: String? = null,
    viewModel: SearchViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val keyword by viewModel.keyword.collectAsState()
    val currentSort by viewModel.sort.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val multiLoading by viewModel.multiLoading.collectAsState()
    val shouldScrollToTop by viewModel.shouldScrollToTop.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val dimension by viewModel.dimension.collectAsState()
    val total by viewModel.total.collectAsState()
    // 输入框初值取 VM 当前关键词：从详情返回重组时恢复显示，避免与结果列表不一致
    var input by remember { mutableStateOf(viewModel.keyword.value) }
    var showTagSheet by remember { mutableStateOf(false) }
    var readFilterName by rememberSaveable { mutableStateOf(com.jmread.ui.browse.ReadFilter.ALL.name) }
    val readFilter = com.jmread.ui.browse.ReadFilter.valueOf(readFilterName)
    // 筛选模式下的客户端翻页（累积数据按 20 条一页切片，纯本地不请求）
    var filterPage by rememberSaveable { mutableStateOf(1) }
    val listState = rememberLazyGridState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // 筛选激活时后台自动拉取剩余分页（每页加载完自动触发下一页，串行不并发）
    LaunchedEffect(readFilter, comics, loading, endReached) {
        if (readFilter != com.jmread.ui.browse.ReadFilter.ALL && !loading && !endReached && comics.isNotEmpty()) {
            viewModel.loadMore()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadTags()
    }

    // 从详情页标签点击进入：自动填入关键词并立即搜索（每次导航都是新实例，只执行一次；
    // 已搜索过则跳过，避免从详情返回重组时重新搜索重置结果与滚动状态）
    LaunchedEffect(initialKeyword) {
        val kw = initialKeyword?.trim().orEmpty()
        if (kw.isNotEmpty() && !viewModel.hasSearched) {
            input = kw
            focusManager.clearFocus()
            viewModel.search(kw, page = 1)
        }
    }

    // 翻页时滚到顶部
    LaunchedEffect(shouldScrollToTop) {
        if (shouldScrollToTop > 0) {
            listState.scrollToItem(0)
        }
    }

    // 保存滚动位置（页面不可见时，如导航到详情）
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(listState.firstVisibleItemIndex)
        }
    }
    // 恢复滚动位置（首次组成为 false，导航返回后为 true）
    LaunchedEffect(viewModel.isScrollStateRestored) {
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
            viewModel.markScrollStateRestored()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text("搜索漫画，多个关键词用空格分隔") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                viewModel.search(input.trim(), page = 1)
            }),
            leadingIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tags.isNotEmpty()) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            showTagSheet = true
                        }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = "官方标签",
                                tint = if (dimension == com.jmread.ui.search.SearchDimension.TAG) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (input.isNotBlank() || keyword.isNotBlank()) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            input = ""
                            viewModel.resetAll()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "重置")
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // 排序切换：VM 回第 1 页后，UI 侧页码指示必须同步归位并回顶——
        // 否则分页条停在旧页码（筛选模式下旧页切片还会短暂显示空列表）
        val onSortChange: (ComicSort) -> Unit = { sort ->
            viewModel.updateSortOnly(sort)
            filterPage = 1
            listState.requestScrollToItem(0)
        }
        // 四维 Tab（综合/作品/作者/标签）：main_tag 实测各有独立结果集与真实 total
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchDimension.entries.forEach { d ->
                item(key = d.name) {
                    FilterChip(
                        selected = dimension == d,
                        onClick = { viewModel.setDimension(d) },
                        label = { Text(d.label) },
                    )
                }
            }
        }
        // 排序：读能力声明（本源无「旧到新」，不出现该选项）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.jmread.core.JmCapabilities.supportedSorts.forEach { s ->
                item(key = s.name) {
                    FilterChip(
                        selected = currentSort == s,
                        onClick = { onSortChange(s) },
                        label = { Text(s.label) },
                    )
                }
            }
            com.jmread.ui.browse.readFilterOptions.forEach { (f, label) ->
                item {
                    FilterChip(
                        selected = readFilter == f,
                        onClick = {
                            readFilterName = f.name
                            viewModel.resetFilterPage()
                            filterPage = 1
                            listState.requestScrollToItem(0)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        // 筛选结果（只随筛选切换/分页加载更新；阅读状态变化仅刷新角标，不实时过滤）
        // 筛选模式：累积数据按服务端页（20 条）切片 + 过滤，页码可点（客户端翻页）
        val displayComics = remember(readFilter, comics, filterPage) {
            if (readFilter == com.jmread.ui.browse.ReadFilter.ALL) {
                comics
            } else {
                comics.drop((filterPage - 1) * 20).take(20).filterByRead(readFilter)
            }
        }
        // 分页条回调（筛选模式=客户端切片换页；普通模式=服务端换页），正常/空态两个分支共用
        val onBarPageChange: (Int) -> Unit = if (readFilter != com.jmread.ui.browse.ReadFilter.ALL) {
            { p ->
                filterPage = p
                // 客户端切片换页：数据整体替换后网格会按索引保留位置，需显式回顶
                listState.requestScrollToItem(0)
            }
        } else {
            { p ->
                // 同步分页条高亮与箭头目标（此前普通模式 filterPage 不更新导致卡在旧值）
                filterPage = p
                viewModel.jumpToPage(p)
                // jumpToPage 内部已有 shouldScrollToTop 兜底；这里立即回顶避免旧列表位置残留
                listState.requestScrollToItem(0)
            }
        }
        if (displayComics.isEmpty()) {
            // 空态也保留分页条（有多页时，如筛选切片为空），避免无路可翻
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            loading -> "搜索中..."
                            searchError != null -> "搜索失败：$searchError"
                            keyword.isBlank() -> "输入关键词开始搜索（多个关键词用空格分隔）"
                            readFilter != com.jmread.ui.browse.ReadFilter.ALL -> "没有符合条件的作品"
                            else -> "没有更多结果"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 单词搜索此前失败会直接闪退；现在显示错误并可一键重试
                    if (searchError != null && !loading && keyword.isNotBlank()) {
                        Text(
                            text = "点击重试",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable { viewModel.search(keyword, page = 1) },
                        )
                    }
                }
            }
            if (totalPages > 1) {
                com.jmread.ui.browse.PaginationBar(
                    currentPage = filterPage,
                    totalPages = totalPages,
                    loadedPages = (comics.size + 19) / 20,
                    onPageChange = onBarPageChange,
                    progressMode = readFilter != com.jmread.ui.browse.ReadFilter.ALL,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (total > 0 && readFilter == com.jmread.ui.browse.ReadFilter.ALL) {
                    Text(
                        text = "共 $total 条结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                ComicGridView(
                    comics = displayComics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    // 纯分页设计：滚动不追加；筛选模式的自动补页由上方 LaunchedEffect 后台链驱动
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                    showTailLoading = readFilter == com.jmread.ui.browse.ReadFilter.ALL,
                )
                if (multiLoading) {
                    Text(
                        text = "后台仍在加载更多结果...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                com.jmread.ui.browse.PaginationBar(
                    currentPage = filterPage,
                    totalPages = totalPages,
                    loadedPages = (comics.size + 19) / 20,
                    onPageChange = onBarPageChange,
                    progressMode = readFilter != com.jmread.ui.browse.ReadFilter.ALL,
                )
            }
        }
    }

    if (showTagSheet && tags.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showTagSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "官方标签",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "点击标签进入标签维度搜索",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                showTagSheet = false
                                viewModel.searchTag(tag)
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
    }
}
