package com.jmread.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.jmread.data.ReaderPrefs
import com.jmread.data.RecentRead
import com.jmread.ui.common.EmptyState
import com.jmread.ui.common.ErrorRetry
import com.jmread.ui.common.InlineErrorBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 阅读历史 VM：加载/单删/清空/撤销；数据仍由 ReaderPrefs 统一落盘 */
class RecentReadsViewModel : ViewModel() {

    private val _items = MutableStateFlow<List<RecentRead>>(emptyList())
    val items: StateFlow<List<RecentRead>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 串行化历史写操作，保证「删除 → 撤销」不会被乱序写回覆盖 */
    private val writeMutex = Mutex()

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _items.value = withContext(Dispatchers.IO) {
                ReaderPrefs.current().recentReadsAsync()
            }
            _loading.value = false
        }
    }

    /** 返回被删除的快照，供 Snackbar 撤销；失败时恢复列表并给出提示 */
    fun delete(ref: String): RecentRead? {
        val removed = _items.value.firstOrNull { it.ref == ref } ?: return null
        _items.value = _items.value.filterNot { it.ref == ref }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeMutex.withLock { ReaderPrefs.current().deleteRecentRead(ref) }
                }
            } catch (e: Exception) {
                _items.value = (listOf(removed) + _items.value)
                    .sortedByDescending { it.ts }
                    .take(100)
                _error.value = "删除失败：${e.message ?: "请稍后重试"}"
            }
        }
        return removed
    }

    /** 返回清空前的完整快照；失败时原样恢复 */
    fun clear(): List<RecentRead> {
        val snapshot = _items.value
        if (snapshot.isEmpty()) return emptyList()
        _items.value = emptyList()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeMutex.withLock { ReaderPrefs.current().clearRecentReads() }
                }
            } catch (e: Exception) {
                _items.value = snapshot
                _error.value = "清空失败：${e.message ?: "请稍后重试"}"
            }
        }
        return snapshot
    }

    fun restore(entries: List<RecentRead>) {
        if (entries.isEmpty()) return
        val merged = (entries + _items.value)
            .associateBy { it.ref }
            .values
            .sortedByDescending { it.ts }
            .take(100)
        _items.value = merged
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeMutex.withLock { ReaderPrefs.current().restoreRecentReads(entries) }
                }
            } catch (e: Exception) {
                _error.value = "撤销失败：${e.message ?: "请稍后重试"}"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecentReadsScreen(
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    viewModel: RecentReadsViewModel = viewModel(),
) {
    val recentReads by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<RecentRead?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除阅读历史") },
            text = { Text("从历史中移除「${target.title.ifBlank { "未知作品" }}」？阅读进度不会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val removed = viewModel.delete(target.ref)
                    if (removed != null) {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "已删除",
                                actionLabel = "撤销",
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restore(listOf(removed))
                            }
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空阅读历史") },
            text = { Text("清空后可在提示条中撤销；阅读进度与已读完状态不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    val snapshot = viewModel.clear()
                    if (snapshot.isNotEmpty()) {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "阅读历史已清空",
                                actionLabel = "撤销",
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) viewModel.restore(snapshot)
                        }
                    }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (recentReads.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) { Text("清空") }
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when {
                loading -> {
                    Text(
                        text = "加载中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                }

                error != null && recentReads.isEmpty() -> ErrorRetry(
                    message = error ?: "加载失败",
                    onRetry = viewModel::load,
                )

                recentReads.isEmpty() -> EmptyState(
                    message = "暂无阅读记录",
                    actionText = "刷新",
                    onAction = viewModel::load,
                )

                else -> Column(Modifier.fillMaxSize()) {
                    if (error != null) {
                        InlineErrorBanner(
                            message = error ?: "",
                            onRetry = viewModel::load,
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(recentReads, key = { "${it.ref}#${it.ts}" }) { recent ->
                            RecentReadRow(
                                recent = recent,
                                onClick = { onOpenComic(recent.ref) },
                                onLongClick = { pendingDelete = recent },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentReadRow(
    recent: RecentRead,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = recent.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp, 96.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = recent.title.ifBlank { "未知作品" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (recent.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = recent.author,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 ${recent.order} 话 · 第 ${recent.pageIndex + 1} 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatReadTime(recent.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}

/** 相对时间：刚刚 / X分钟前 / X小时前 / X天前 / 超过7天显示日期 */
private fun formatReadTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 24 * 3_600_000L -> "${diff / 3_600_000}小时前"
        diff < 7 * 24 * 3_600_000L -> "${diff / (24 * 3_600_000L)}天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(ts))
    }
}
