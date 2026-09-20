package com.jmread.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jmread.core.model.ComicCategory
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicStatus
import com.jmread.core.JmCapabilities
import com.jmread.ui.browse.BrowseViewModel
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.filterByRead

/**
 * 分类 Tab：以网格展示当前源的全部分类。
 * 点击分类进入该分类的漫画流（[CategoryComicsScreen]）。
 * 支持拖拽排序和显示/隐藏设置，本地持久化保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onCategoryClick: (String) -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    // 组合期不做磁盘/SharedPreferences 读取：初值给空设置，在 LaunchedEffect 中加载
    var settings by remember { mutableStateOf(com.jmread.data.CategorySettings.Settings()) }

    LaunchedEffect(Unit) {
        viewModel.loadCategories()
    }
    LaunchedEffect(Unit) {
        settings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.jmread.data.CategorySettings.get()
        }
    }

    val displayCategories = remember(categories, settings) {
        val filtered = categories.filter { it.id !in settings.hidden }
        if (settings.order.isNotEmpty()) {
            val orderMap = settings.order.withIndex().associate { (i, id) -> id to i }
            filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
        } else {
            filtered
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        if (displayCategories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (categories.isEmpty()) "当前源暂无分类" else "所有分类已隐藏",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(displayCategories, key = { it.id }) { category ->
                    CategoryCard(category = category, onClick = { onCategoryClick(category.id) })
                }
            }
        }
        if (showSettings) {
            CategoryReorderDialog(
                categories = categories,
                currentSettings = settings,
                onSettingsChange = { newSettings ->
                    settings = newSettings
                    com.jmread.data.CategorySettings.save(newSettings)
                },
                onDismiss = { showSettings = false },
            )
        }
    }
}

/**
 * 分类漫画流：固定一个分类，分页浏览，当前仅提供排序筛选。
 *
 * 注：BrowseViewModel 具备连载状态筛选（setStatus / supportsStatusFilter），但本页面
 * 未渲染状态筛选入口，因此该能力在此处于"已实现未接线"状态。此前注释声称"支持排序 +
 * 连载状态筛选"，与界面不符。要启用需在此补状态 chip 并在 ComicGridView 的筛选区呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryComicsScreen(
    categoryId: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val listState = rememberLazyGridState()

    // 保存滚动位置
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(listState.firstVisibleItemIndex)
        }
    }
    // 恢复滚动位置
    LaunchedEffect(viewModel.isScrollStateRestored) {
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
            viewModel.markScrollStateRestored()
        }
    }

    val categoryTitle = categories.firstOrNull { it.id == categoryId }?.title ?: "分类"
    val supportedSorts = remember { JmCapabilities.supportedSorts }

    LaunchedEffect(categoryId) {
        viewModel.loadCategories()
        // 同分类且已有数据时（从详情返回重组）VM 内部会跳过重载，保留累积分页与滚动状态
        viewModel.loadComics(page = 1, category = categoryId, reloadKey = categoryId)
    }

    // 当前排序不在能力声明内时回退到默认
    LaunchedEffect(supportedSorts) {
        if (viewModel.sort.value !in supportedSorts) {
            viewModel.setSort(supportedSorts.first())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 排序 + 阅读状态筛选
            var readFilterName by rememberSaveable { mutableStateOf(com.jmread.ui.browse.ReadFilter.ALL.name) }
            val readFilter = com.jmread.ui.browse.ReadFilter.valueOf(readFilterName)
            // 筛选模式下的客户端翻页（累积数据按 20 条一页切片，纯本地不请求）
            var filterPage by rememberSaveable { mutableStateOf(1) }
            // 筛选激活时后台自动拉取剩余分页（每页加载完自动触发下一页，串行不并发）
            LaunchedEffect(readFilter, comics, loading, endReached) {
                if (readFilter != com.jmread.ui.browse.ReadFilter.ALL && !loading && !endReached && comics.isNotEmpty()) {
                    viewModel.loadMore()
                }
            }
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
            // 分页条回调（筛选模式=客户端切片换页；全部模式=服务端单页换页），正常/空态两个分支共用
            val onBarPageChange: (Int) -> Unit = if (readFilter != com.jmread.ui.browse.ReadFilter.ALL) {
                { p ->
                    filterPage = p
                    // 客户端切片换页：数据整体替换后网格会按索引保留位置，需显式回顶
                    listState.requestScrollToItem(0)
                }
            } else {
                { p ->
                    filterPage = p
                    viewModel.jumpToPage(p)
                    // 服务端换页：旧列表尚在展示时先回顶，新数据替换后仍停留在顶部
                    listState.requestScrollToItem(0)
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
            } else if (displayComics.isEmpty() && !loading) {
                // 空态也保留分页条（有多页时），避免筛选切片为空时无路可翻
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (readFilter != com.jmread.ui.browse.ReadFilter.ALL) {
                            "没有符合条件的作品"
                        } else {
                            "该分类下暂无符合条件的漫画"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                // 跳页失败但旧内容还在展示：必须显式提示，否则旧页内容顶着新页码、
                // 用户以为已翻页成功（此前错误只在列表为空时才显示）
                if (error != null && displayComics.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "第 $filterPage 页加载失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable { viewModel.jumpToPage(filterPage) },
                        )
                    }
                }
                ComicGridView(
                    comics = displayComics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    // 纯分页设计（v1.5.14）：滚动不追加；筛选模式的自动补页由上方 LaunchedEffect 后台链驱动
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                    showTailLoading = readFilter == com.jmread.ui.browse.ReadFilter.ALL,
                )
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
}

@Composable
private fun CategoryCard(category: ComicCategory, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!category.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = category.coverUrl,
                        contentDescription = category.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = category.title.take(1),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = category.title,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
    }
}
