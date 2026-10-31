package com.jmread.ui.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmCapabilities
import com.jmread.core.model.ComicSubCategory
import com.jmread.data.OfficialTags
import com.jmread.ui.browse.BrowseViewModel
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.PaginationBar
import com.jmread.ui.browse.filterByRead

/**
 * 分类 Tab：官方分类 + 官方标签墙的紧凑单页。
 *
 * 标签词表是固定的（[OfficialTags]），不再每次进入页面请求 `/categories`；
 * 点标签进入该标签的作品流（main_tag=3），而不是把它变成置顶。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryScreen(
    onCategoryClick: (String) -> Unit = {},
    onSubcategoryClick: (String, String) -> Unit = { _, _ -> },
    onTagClick: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    var selectedCategoryId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // 仓库层有内存缓存：同一进程内重复进入分类页不会重复请求。
        viewModel.loadCategories()
    }

    val topLevelCategories = categories

    // 显示层去重：只合并「同名顶级分类/同名子分类」，不把 slug 相同但语义不同的入口合并
    // （顶级 Cosplay 与 其他類/角色扮演 是两个内容位，不能互删）。
    val topLevelSlugs = remember(topLevelCategories) {
        topLevelCategories.map { it.id.lowercase() }.toSet()
    }
    val topLevelTitles = remember(topLevelCategories) {
        topLevelCategories.map { it.title.lowercase() }.toSet()
    }

    val selectedCategory = topLevelCategories.firstOrNull { it.id == selectedCategoryId }
        ?: topLevelCategories.firstOrNull()
    val selectedSubCategories = remember(selectedCategory, topLevelSlugs, topLevelTitles) {
        selectedCategory?.subCategories.dedupedAgainst(topLevelSlugs, topLevelTitles)
    }
    val dedupedSubCount = topLevelCategories.sumOf {
        it.subCategories.dedupedAgainst(topLevelSlugs, topLevelTitles).size
    }

    LaunchedEffect(topLevelCategories) {
        selectedCategoryId = when {
            topLevelCategories.isEmpty() -> null
            topLevelCategories.any { it.id == selectedCategoryId } -> selectedCategoryId
            else -> topLevelCategories.first().id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类") },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // ── 分类 ──────────────────────────────────────────────────
            CategorySectionHead(
                title = "分类",
                count = if (topLevelCategories.isEmpty()) {
                    null
                } else {
                    "${topLevelCategories.size} 个顶级分类 · $dedupedSubCount 个子分类"
                },
            )
            if (topLevelCategories.isEmpty()) {
                CategoryHint(if (categories.isEmpty()) "分类加载中…" else "暂无分类")
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topLevelCategories.forEach { category ->
                        FilterChip(
                            selected = category.id == selectedCategory?.id,
                            onClick = { selectedCategoryId = category.id },
                            label = { Text(category.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }

                selectedCategory?.let { category ->
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = false,
                                onClick = { onCategoryClick(category.id) },
                                label = { Text("全部") },
                            )
                        }
                        items(selectedSubCategories, key = { "${category.id}|${it.slug}" }) { sub ->
                            FilterChip(
                                selected = false,
                                onClick = { onSubcategoryClick(category.id, sub.name) },
                                label = { Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            // ── 官方标签墙（固定词表，点标签 = 该标签的作品流）───────────
            CategorySectionHead(
                title = "官方标签",
                count = "${OfficialTags.groups.size} 组 · ${OfficialTags.all.size} 个",
            )
            CategoryHint("点击标签查看该标签下的作品")
            OfficialTags.groups.forEachIndexed { index, group ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 9.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                CategoryGroupTitle(
                    title = group.title,
                    count = "${group.tags.size} 个",
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    group.tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { onTagClick(tag) },
                            label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

/**
 * 分类详情：顶级分类直接走 /categories/filter；
 * 二级分类用「子分类名 + 顶级分类 c=」搜索，作为官方结构的可用快捷入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryComicsScreen(
    categoryId: String,
    subCategory: String? = null,
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
    val supportedSorts = remember { JmCapabilities.supportedSorts }

    val routeSub = subCategory?.takeIf { it.isNotBlank() }.orEmpty()
    var currentSub by rememberSaveable(categoryId, routeSub) { mutableStateOf(routeSub) }
    var readFilterName by rememberSaveable(categoryId, currentSub) {
        mutableStateOf(com.jmread.ui.browse.ReadFilter.ALL.name)
    }
    var filterPage by rememberSaveable(categoryId, currentSub) { mutableStateOf(1) }
    val readFilter = com.jmread.ui.browse.ReadFilter.valueOf(readFilterName)

    val categoryTitle = categories.firstOrNull { it.id == categoryId }?.title ?: "分类"
    val title = if (currentSub.isBlank()) categoryTitle else "$categoryTitle · $currentSub"
    val reloadKey = "$categoryId|${currentSub.ifBlank { "" }}"

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(listState.firstVisibleItemIndex)
        }
    }
    LaunchedEffect(viewModel.isScrollStateRestored) {
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
            viewModel.markScrollStateRestored()
        }
    }

    LaunchedEffect(categoryId, currentSub) {
        viewModel.loadCategories()
        viewModel.loadComics(
            page = 1,
            category = categoryId,
            subCategory = currentSub.ifBlank { null },
            reloadKey = reloadKey,
        )
    }
    LaunchedEffect(currentSub) {
        filterPage = 1
        listState.requestScrollToItem(0)
    }

    val category = categories.firstOrNull { it.id == categoryId }
    val topLevelSlugs = remember(categories) {
        categories.map { it.id.lowercase() }.toSet()
    }
    val topLevelTitles = remember(categories) {
        categories.map { it.title.lowercase() }.toSet()
    }
    val subCategories = remember(category, topLevelSlugs, topLevelTitles) {
        category?.subCategories.dedupedAgainst(topLevelSlugs, topLevelTitles)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            if (subCategories.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = currentSub.isBlank(),
                            onClick = { currentSub = "" },
                            label = { Text("全部") },
                        )
                    }
                    items(subCategories, key = { "${categoryId}|${it.slug}" }) { sub ->
                        FilterChip(
                            selected = currentSub == sub.name,
                            onClick = { currentSub = sub.name },
                            label = { Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(supportedSorts, key = { it.name }) { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = {
                            viewModel.setSort(s)
                            filterPage = 1
                            listState.requestScrollToItem(0)
                        },
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

            val displayComics = remember(readFilter, comics, filterPage) {
                if (readFilter == com.jmread.ui.browse.ReadFilter.ALL) {
                    comics
                } else {
                    comics.drop((filterPage - 1) * 20).take(20).filterByRead(readFilter)
                }
            }
            val onBarPageChange: (Int) -> Unit = if (readFilter != com.jmread.ui.browse.ReadFilter.ALL) {
                { p ->
                    filterPage = p
                    listState.requestScrollToItem(0)
                }
            } else {
                { p ->
                    filterPage = p
                    viewModel.jumpToPage(p)
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            readFilter != com.jmread.ui.browse.ReadFilter.ALL -> "没有符合条件的作品"
                            currentSub.isNotBlank() -> "「$currentSub」暂无匹配作品"
                            else -> "该分类下暂无符合条件的漫画"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (totalPages > 1) {
                    PaginationBar(
                        currentPage = filterPage,
                        totalPages = totalPages,
                        loadedPages = (comics.size + 19) / 20,
                        onPageChange = onBarPageChange,
                        progressMode = readFilter != com.jmread.ui.browse.ReadFilter.ALL,
                    )
                }
            } else {
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
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                    showTailLoading = readFilter == com.jmread.ui.browse.ReadFilter.ALL,
                )
                PaginationBar(
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
private fun CategorySectionHead(title: String, count: String?) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 7.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (!count.isNullOrBlank()) {
            Text(
                text = count,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

@Composable
private fun CategoryGroupTitle(title: String, count: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = count,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun CategoryHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
    )
}

/** 同名顶级分类不在子分类里重复展示（例如顶级 3D 与 其他類/3D）。 */
private fun List<ComicSubCategory>?.dedupedAgainst(
    topLevelSlugs: Set<String>,
    topLevelTitles: Set<String>,
): List<ComicSubCategory> =
    orEmpty().filter { sub ->
        sub.slug.lowercase() !in topLevelSlugs && sub.name.lowercase() !in topLevelTitles
    }
