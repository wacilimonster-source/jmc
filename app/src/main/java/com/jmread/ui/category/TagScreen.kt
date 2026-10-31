package com.jmread.ui.category

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSummary
import com.jmread.data.GridSettings
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.PaginationBar
import com.jmread.ui.common.ComicGridSkeleton
import com.jmread.ui.common.ErrorRetry
import com.jmread.ui.common.InlineErrorBanner
import com.jmread.ui.search.SearchDimension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 标签作品流 VM：main_tag=3 的标签维度搜索分页。
 *
 * 分类页的每个标签 chip 都通过这里打开作品列表，标签本身不再承担「置顶」语义。
 */
class TagViewModel : ViewModel() {

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total

    private var tag: String = ""
    private var loadGeneration = 0

    fun load(tag: String, page: Int = 1, reloadKey: String? = null) {
        if (this.tag != tag) {
            this.tag = tag
            _comics.value = emptyList()
            _currentPage.value = 1
            _total.value = 0
        } else if (reloadKey == tag && _comics.value.isNotEmpty()) {
            return // 同一标签返回重组：保留当前页与数据（从详情返回不跳回第 1 页）
        }
        jumpToPage(page)
    }

    fun jumpToPage(page: Int) {
        val gen = ++loadGeneration
        _endReached.value = true // 防加载期间触底重复触发
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val result = JmRepository.searchDimension(
                    keyword = tag,
                    page = page,
                    sort = _sort.value,
                    mainTag = SearchDimension.TAG.mainTag,
                )
                if (gen != loadGeneration) return@launch
                _comics.value = result.items
                _totalPages.value = result.pages.coerceAtLeast(1)
                _endReached.value = page >= result.pages
                _currentPage.value = page
                _total.value = result.total
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) {
                    _endReached.value = false
                    _error.value = e.message ?: "加载失败"
                }
            } finally {
                if (gen == loadGeneration) _loading.value = false
            }
        }
    }

    fun setSort(sort: ComicSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        jumpToPage(1)
    }
}

/** 标签作品页：标签说明 + 排序 + 分页网格 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    tag: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: TagViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val total by viewModel.total.collectAsState()
    val listState = rememberLazyGridState()
    val columns by GridSettings.columnsFlow.collectAsState()

    LaunchedEffect(tag) {
        viewModel.load(tag, page = 1, reloadKey = tag)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Text(
                text = "标签「$tag」下的作品",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(JmCapabilities.supportedSorts, key = { it.name }) { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { viewModel.setSort(s) },
                        label = { Text(s.label) },
                    )
                }
            }
            if (total > 0) {
                Text(
                    text = "共 $total 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                error != null && comics.isEmpty() -> ErrorRetry(
                    message = error ?: "加载失败",
                    onRetry = { viewModel.jumpToPage(1) },
                    modifier = Modifier.weight(1f),
                )
                comics.isEmpty() && loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    if (error != null) {
                        InlineErrorBanner(
                            message = error ?: "",
                            onRetry = { viewModel.jumpToPage(currentPage) },
                        )
                    }
                    ComicGridView(
                        comics = comics,
                        loading = false,
                        endReached = endReached,
                        listState = listState,
                        onLoadMore = {},
                        onComicClick = onComicClick,
                        modifier = Modifier.weight(1f),
                        showTailLoading = false,
                    )
                    PaginationBar(
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageChange = { p ->
                            viewModel.jumpToPage(p)
                            listState.requestScrollToItem(0)
                        },
                    )
                }
            }
        }
    }
}
