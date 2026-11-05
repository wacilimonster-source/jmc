package com.jmread.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.model.ComicSort
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.ReadFilter
import com.jmread.ui.browse.filterByRead
import com.jmread.ui.browse.readFilterOptions

/** 搜索页：输入框（返回/清文字）+ 四维固定排 + 已选条件摘要与筛选面板 + 结果网格 + 分页条 */
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
    // 搜索历史（10 条去重置顶）：空态时展示，点词直达、页内可清空
    val history by com.jmread.data.SearchHistory.keywords.collectAsState()
    // 输入框初值取 VM 当前关键词：从详情返回重组时恢复显示，避免与结果列表不一致
    var input by remember { mutableStateOf(viewModel.keyword.value) }
    var showTagSheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var readFilterName by rememberSaveable { mutableStateOf(ReadFilter.ALL.name) }
    val readFilter = ReadFilter.valueOf(readFilterName)
    // 筛选模式下的客户端翻页（累积数据按 20 条一页切片，纯本地不请求）
    var filterPage by rememberSaveable { mutableStateOf(1) }
    val listState = rememberLazyGridState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // I04（阶段 B）：本地筛选改为「先过滤已加载集合，再按 20 条分页」；
    // 当前页不满且源可能还有数据时按需补页；失败停止自动补页，等显式重试。
    val matchedCount = remember(readFilter, comics) { comics.filterByRead(readFilter).size }
    LaunchedEffect(readFilter, comics, loading, endReached, searchError, filterPage) {
        if (readFilter != ReadFilter.ALL && !loading && !endReached &&
            searchError == null && matchedCount < filterPage * com.jmread.ui.browse.FILTER_PAGE_SIZE
        ) {
            viewModel.loadMore()
        }
    }
    // 数据收缩（切换查询/条件）后页码可能越界：收敛回最后一页
    val matchedPages = com.jmread.ui.browse.filteredPageCount(matchedCount)
    LaunchedEffect(matchedPages, readFilter) {
        if (readFilter != ReadFilter.ALL && filterPage > matchedPages) {
            filterPage = matchedPages
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
        // Material3 的图标槽按「lambda 是否为 null」决定是否保留空间；
        // 传一个条件返回空的 lambda 也会左右各留出图标位，导致占位文字看起来居中。
        val leadingIcon: @Composable (() -> Unit)? = if (onBack != null) {
            {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        } else {
            null
        }
        // I03：叉号只清空输入草稿；已提交查询与已选条件不受影响，旧结果不伪装成新查询
        val showClearDraft = input.isNotBlank()
        val trailingIcon: @Composable (() -> Unit)? = if (showClearDraft) {
            {
                IconButton(onClick = {
                    input = ""
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除输入文字")
                }
            }
        } else {
            null
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            // Material3 图标槽按 lambda 是否为 null 决定保留空间；
            // 这里再显式 start 对齐，保证占位/已输入文字始终靠左
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Start),
            placeholder = {
                Text(
                    text = "搜索漫画，多个关键词用空格分隔",
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                // 新查询必须把筛选页码与滚动位置一起归零，否则会停在上一个查询的第 N 页
                filterPage = 1
                viewModel.search(input.trim(), page = 1)
                listState.requestScrollToItem(0)
            }),
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // I02：四维固定为一排（不随排序/阅读条件滚动），选中态带下划线
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            SearchDimension.entries.forEach { d ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                            indication = null,
                        ) {
                            viewModel.setDimension(d)
                            filterPage = 1
                            listState.requestScrollToItem(0)
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = d.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (dimension == d) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .background(
                                color = if (dimension == d) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }

        // I02：排序与阅读条件收进筛选面板；结果上方常驻已选条件摘要
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = buildString {
                    append(currentSort.label)
                    append(" · ")
                    append(readFilterOptions.first { it.first == readFilter }.second)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                tint = if (readFilter != ReadFilter.ALL || currentSort != ComicSort.DD) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "筛选",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showFilterSheet = true }
                    .padding(start = 6.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            )
            if (tags.isNotEmpty()) {
                Text(
                    text = "标签",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            focusManager.clearFocus()
                            showTagSheet = true
                        }
                        .padding(start = 10.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                )
            }
        }

        // 筛选结果（只随筛选切换/分页加载更新；阅读状态变化仅刷新角标，不实时过滤）
        // I04（阶段 B）：先过滤已加载集合再切片——匹配项分散在各服务端分片时页页可达
        val displayComics = remember(readFilter, comics, filterPage) {
            com.jmread.ui.browse.filterThenPage(comics, readFilter, filterPage)
        }
        // 筛选页码按「过滤后的已加载结果」计算，不能沿用服务端 80 条/页的 totalPages
        val barTotalPages = if (readFilter != ReadFilter.ALL) {
            matchedPages
        } else {
            totalPages
        }
        val barLoadedPages = if (readFilter != ReadFilter.ALL) {
            matchedPages
        } else {
            (comics.size + 19) / 20
        }
        // 分页条回调（筛选模式=客户端切片换页；普通模式=服务端换页），正常/空态两个分支共用
        val onBarPageChange: (Int) -> Unit = if (readFilter != ReadFilter.ALL) {
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
            // 空态也保留分页条（有多页时，如筛选切片为空），避免无路可翻。
            // 提示与历史从上往下排，不整块居中：搜索页常有大片空白，居中会显得提示“悬空”。
            Column(Modifier.weight(1f).fillMaxWidth()) {
                // 空关键词不重复提示（输入框占位已经说明怎么搜），把纵向空间留给搜索历史
                // I04：无结果 / 正在补齐 / 补齐失败 三态区分；不声称全站无结果
                val emptyHint = when {
                    loading -> if (readFilter != ReadFilter.ALL && comics.isNotEmpty()) {
                        "正在查找更多结果…（已加载 ${comics.size} 部，匹配 $matchedCount 部）"
                    } else {
                        "搜索中..."
                    }
                    searchError != null && readFilter != ReadFilter.ALL && comics.isNotEmpty() ->
                        "补页失败：$searchError（已保留已加载内容）"
                    searchError != null -> "搜索失败：$searchError"
                    keyword.isBlank() -> null
                    readFilter != ReadFilter.ALL && matchedCount == 0 ->
                        "已加载 ${comics.size} 部，没有匹配的作品"
                    readFilter != ReadFilter.ALL ->
                        "已加载 ${comics.size} 部，匹配 $matchedCount 部；当前页没有更多匹配"
                    else -> "没有更多结果"
                }
                if (emptyHint != null) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                // 筛选态空页：继续查找（按需补页）或清除条件，不声称全站无结果（I04）
                if (!loading && searchError == null && keyword.isNotBlank() &&
                    readFilter != ReadFilter.ALL
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (!endReached) {
                            Text(
                                text = "继续查找",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.loadMore() },
                            )
                        }
                        Text(
                            text = "清除条件",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                readFilterName = ReadFilter.ALL.name
                                filterPage = 1
                                listState.requestScrollToItem(0)
                            },
                        )
                    }
                }
                // 补页失败：显式重试（自动补页链已停止，不无限重拉）（I04）
                if (!loading && searchError != null && readFilter != ReadFilter.ALL &&
                    comics.isNotEmpty() && keyword.isNotBlank()
                ) {
                    Text(
                        text = "重试补页",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { viewModel.loadMore() },
                    )
                }
                // 单词搜索此前失败会直接闪退；现在显示错误并可一键重试
                if (searchError != null && !loading && keyword.isNotBlank()) {
                    Text(
                        text = "点击重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable {
                                filterPage = 1
                                viewModel.search(keyword, page = 1)
                                listState.requestScrollToItem(0)
                            },
                    )
                }
                // 搜索历史：仅在「无结果、未加载、无错误」时展示，避免挤占结果与错误态
                if (history.isNotEmpty() && keyword.isBlank() && !loading && searchError == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "清空",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { com.jmread.data.SearchHistory.clear() },
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            history.forEach { kw ->
                                SuggestionChip(
                                    onClick = {
                                        input = kw
                                        filterPage = 1
                                        focusManager.clearFocus()
                                        viewModel.search(kw, page = 1)
                                    },
                                    label = { Text(kw) },
                                )
                            }
                        }
                    }
                }
            }
            if (barTotalPages > 1) {
                com.jmread.ui.browse.PaginationBar(
                    currentPage = filterPage,
                    totalPages = barTotalPages,
                    loadedPages = barLoadedPages,
                    onPageChange = onBarPageChange,
                    progressMode = readFilter != ReadFilter.ALL,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (readFilter != ReadFilter.ALL) {
                    Text(
                        text = "已加载 ${comics.size} 部，匹配 $matchedCount 部（本地筛选范围）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                } else if (multiLoading) {
                    Text(
                        text = "后台仍在加载更多结果...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                if (searchError != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "加载失败：$searchError",
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
                                .clickable {
                                    filterPage = 1
                                    viewModel.search(keyword, page = 1)
                                    listState.requestScrollToItem(0)
                                },
                        )
                    }
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
                    showTailLoading = readFilter == ReadFilter.ALL,
                )
                com.jmread.ui.browse.PaginationBar(
                    currentPage = filterPage,
                    totalPages = barTotalPages,
                    loadedPages = barLoadedPages,
                    onPageChange = onBarPageChange,
                    progressMode = readFilter != ReadFilter.ALL,
                    // 条数与分页合并到同一行，省掉网格上方的结果数文本行
                    summary = if (total > 0 && readFilter == ReadFilter.ALL) {
                        "共 $total 条"
                    } else {
                        null
                    },
                )
            }
        }
    }

    // I02：筛选面板——先编辑（草稿）再应用；取消面板不提交；重置条件独立于输入框叉号
    if (showFilterSheet) {
        var draftSortName by remember { mutableStateOf(currentSort.name) }
        var draftReadName by remember { mutableStateOf(readFilterName) }
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Text("筛选条件", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "提交新查询才会更换结果；取消面板不应用改动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "排序",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.jmread.core.JmCapabilities.supportedSorts.forEach { s ->
                        FilterChip(
                            selected = draftSortName == s.name,
                            onClick = { draftSortName = s.name },
                            label = { Text(s.label) },
                        )
                    }
                }
                Text(
                    text = "阅读状态 · 本地结果范围",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    readFilterOptions.forEach { (f, label) ->
                        FilterChip(
                            selected = draftReadName == f.name,
                            onClick = { draftReadName = f.name },
                            label = { Text(label) },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    TextButton(onClick = {
                        draftSortName = ComicSort.DD.name
                        draftReadName = ReadFilter.ALL.name
                    }) { Text("重置条件") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showFilterSheet = false }) { Text("取消") }
                    androidx.compose.material3.Button(
                        onClick = {
                            showFilterSheet = false
                            val newSort = ComicSort.valueOf(draftSortName)
                            if (newSort != currentSort) {
                                viewModel.updateSortOnly(newSort)
                            }
                            if (draftReadName != readFilterName) {
                                readFilterName = draftReadName
                            }
                            if (newSort != currentSort || draftReadName != readFilterName) {
                                filterPage = 1
                                listState.requestScrollToItem(0)
                            }
                        },
                    ) { Text("应用") }
                }
            }
        }
    }

    if (showTagSheet && tags.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showTagSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
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
                                // 输入框与结果保持一致：标签搜索的关键词就是标签本身
                                input = tag
                                filterPage = 1
                                listState.requestScrollToItem(0)
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
    }
}
