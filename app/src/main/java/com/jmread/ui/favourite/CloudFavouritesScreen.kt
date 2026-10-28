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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSummary
import com.jmread.data.GridSettings
import com.jmread.ui.browse.ComicGridView
import com.jmread.ui.browse.PaginationBar
import com.jmread.ui.common.ComicGridSkeleton
import com.jmread.ui.common.EmptyState
import com.jmread.ui.common.ErrorRetry
import com.jmread.ui.common.InlineErrorBanner
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

    /** 正在取消收藏的作品 id（防止重复点击/重复请求） */
    private val _removingId = MutableStateFlow<String?>(null)
    val removingId: StateFlow<String?> = _removingId

    /** 请求代际：连点页码/切排序时旧响应不得覆盖新选择 */
    private var loadGeneration = 0
    private var loadedOnce = false
    private var lastLoadAt = 0L
    private var lastSeenFavouriteVersion = JmRepository.favouriteVersion

    fun load(page: Int, silent: Boolean = false) {
        val gen = ++loadGeneration
        loadedOnce = true
        lastLoadAt = System.currentTimeMillis()
        viewModelScope.launch {
            if (!silent) _loading.value = true
            _error.value = null
            try {
                val r = JmRepository.favourites(page, _orderBy.value)
                if (gen != loadGeneration) return@launch
                _comics.value = r.items
                _totalPages.value = r.pages.coerceAtLeast(1)
                _currentPage.value = page
                _total.value = r.total
                lastSeenFavouriteVersion = JmRepository.favouriteVersion
                lastLoadAt = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) _error.value = e.message ?: "加载失败"
            } finally {
                if (gen == loadGeneration) _loading.value = false
            }
        }
    }

    /**
     * 从详情页返回时刷新：只有收藏状态真的变过，或列表已陈旧 30 秒才重拉，
     * 避免每次返回都重置分页/滚动位置。
     */
    fun refreshOnResume() {
        if (!loadedOnce) return
        val changed = JmRepository.favouriteVersion != lastSeenFavouriteVersion
        if (!changed && System.currentTimeMillis() - lastLoadAt < 30_000) return
        load(_currentPage.value, silent = true)
    }

    fun setOrderBy(orderBy: String) {
        if (_orderBy.value == orderBy) return
        _orderBy.value = orderBy
        load(1)
    }

    /** 列表内取消收藏：以服务端翻转结果为准，成功后本地移除并更新计数 */
    fun removeFavourite(comic: ComicSummary) {
        if (_removingId.value != null) return
        _removingId.value = comic.id
        viewModelScope.launch {
            try {
                val nowFavourite = JmRepository.favourite(comic.id)
                if (nowFavourite) {
                    // 列表可能已过期（该项其实已不在云端），不要误删本地；重拉校准
                    _error.value = "服务端返回该作品仍为收藏态，已重新加载列表"
                    load(_currentPage.value, silent = true)
                    return@launch
                }
                val remaining = _comics.value.filterNot { it.id == comic.id }
                _comics.value = remaining
                _total.value = (_total.value - 1).coerceAtLeast(0)
                // 收藏页每页 80 条；移除后同步收敛总页数，避免分页条停留在不存在的页
                _totalPages.value = ((_total.value + 79) / 80).coerceAtLeast(1)
                lastSeenFavouriteVersion = JmRepository.favouriteVersion
                if (remaining.isEmpty() && _currentPage.value > 1) {
                    load(_currentPage.value - 1)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "取消收藏失败：${e.message ?: "请稍后重试"}"
            } finally {
                _removingId.value = null
            }
        }
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
    val removingId by viewModel.removingId.collectAsState()
    val columns by GridSettings.columnsFlow.collectAsState()
    val listState = rememberLazyGridState()
    var pendingRemove by remember { mutableStateOf<ComicSummary?>(null) }

    LaunchedEffect(Unit) { viewModel.load(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshOnResume() }

    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { if (removingId == null) pendingRemove = null },
            title = { Text("取消收藏") },
            text = { Text("从云端收藏中移除「${target.title}」？") },
            confirmButton = {
                TextButton(
                    enabled = removingId == null,
                    onClick = {
                        pendingRemove = null
                        viewModel.removeFavourite(target)
                    },
                ) { Text("取消收藏") }
            },
            dismissButton = {
                TextButton(
                    enabled = removingId == null,
                    onClick = { pendingRemove = null },
                ) { Text("保留") }
            },
        )
    }

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
                error != null && comics.isEmpty() -> ErrorRetry(
                    message = error ?: "加载失败",
                    onRetry = { viewModel.load(currentPage) },
                    modifier = Modifier.weight(1f),
                )
                comics.isEmpty() && loading -> ComicGridSkeleton(
                    columns = columns,
                    modifier = Modifier.weight(1f),
                )
                comics.isEmpty() -> EmptyState(
                    message = "云端还没有收藏\n在详情页点心形即可收藏到云端",
                    modifier = Modifier.weight(1f),
                    actionText = "刷新",
                    onAction = { viewModel.load(1) },
                )
                else -> {
                    if (error != null) {
                        InlineErrorBanner(
                            message = error ?: "",
                            onRetry = { viewModel.load(currentPage, silent = true) },
                        )
                    }
                    ComicGridView(
                        comics = comics,
                        loading = false,
                        endReached = currentPage >= totalPages,
                        listState = listState,
                        onLoadMore = {},
                        onComicClick = onComicClick,
                        onComicLongClick = { id ->
                            comics.firstOrNull { it.id == id }?.let { pendingRemove = it }
                        },
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
