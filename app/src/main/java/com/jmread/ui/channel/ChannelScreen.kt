package com.jmread.ui.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.PageResult
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.PaginationBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 频道 VM（搜索型频道：汉化组/去码/全彩化）。
 * 频道定义来自 [JmRepository.channels]，内容流 = 频道关键词的搜索分页。
 */
class ChannelViewModel : ViewModel() {

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description

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

    private var channelId: String = ""
    private var loadGeneration = 0

    fun load(channelId: String, page: Int = 1, reloadKey: String? = null) {
        if (this.channelId != channelId) {
            this.channelId = channelId
            JmRepository.channelOf(channelId)?.let {
                _title.value = it.label
                _description.value = it.description
            }
        } else if (reloadKey == channelId && _comics.value.isNotEmpty()) {
            return // 同频道返回重组：保留累积分页
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
                val result = JmRepository.channelFeed(channelId, page, _sort.value)
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

/** 频道页（禁漫汉化组 / 禁漫去码 / 全彩化）：说明 + 排序 + 分页网格 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelId: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: ChannelViewModel = viewModel(),
) {
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val total by viewModel.total.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyGridState()

    LaunchedEffect(channelId) {
        viewModel.load(channelId, page = 1)
        listState.requestScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "频道" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
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
                error != null && comics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = error ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                            Text(
                                text = "点击重试",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .clickable { viewModel.jumpToPage(1) },
                            )
                        }
                    }
                }
                comics.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
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
                            listState.requestScrollToItem(0)
                        },
                    )
                }
            }
        }
    }
}
