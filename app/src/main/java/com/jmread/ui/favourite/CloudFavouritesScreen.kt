package com.jmread.ui.favourite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSummary
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.PaginationBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 云端收藏 VM：/favorite 列表（data 键 list，total 字符串数字——DTO 已兼容）。
 * 排序 o=mr 收藏时间 / o=mp 更新时间。
 */
class CloudFavouritesViewModel : ViewModel() {

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _orderBy = MutableStateFlow("mr")
    val orderBy: StateFlow<String> = _orderBy

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total

    /** 请求代际：连点页码/切排序时旧响应不得覆盖新选择 */
    private var loadGeneration = 0

    fun load(page: Int) {
        val gen = ++loadGeneration
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val r = JmRepository.favourites(page, _orderBy.value)
                _comics.value = r.items
                _totalPages.value = r.pages.coerceAtLeast(1)
                _currentPage.value = page
                _total.value = r.total
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) _error.value = e.message ?: "加载失败"
            } finally {
                if (gen == loadGeneration) _loading.value = false
            }
        }
    }

    fun setOrderBy(orderBy: String) {
        if (_orderBy.value == orderBy) return
        _orderBy.value = orderBy
        load(1)
    }
}

/** 云端收藏页（登录后「我的」入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFavouritesScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: CloudFavouritesViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val orderBy by viewModel.orderBy.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val total by viewModel.total.collectAsState()
    val listState = rememberLazyGridState()

    LaunchedEffect(Unit) { viewModel.load(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云端收藏") },
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
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = orderBy == "mr",
                    onClick = { viewModel.setOrderBy("mr") },
                    label = { Text("收藏时间") },
                )
                FilterChip(
                    selected = orderBy == "mp",
                    onClick = { viewModel.setOrderBy("mp") },
                    label = { Text("更新时间") },
                )
                if (total > 0) {
                    Text(
                        text = "共 $total 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            when {
                error != null && comics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
                comics.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                comics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "云端还没有收藏\n在详情页点心形即可收藏到云端",
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
                            viewModel.load(p)
                            listState.requestScrollToItem(0)
                        },
                    )
                }
            }
        }
    }
}
