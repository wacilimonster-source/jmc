package com.jmread.ui.rank

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.RankTab
import com.jmread.ui.browse.ComicGridView
import kotlinx.coroutines.launch

/**
 * 默认档位 = 新晋热榜（H24）。
 *
 * H24 不是服务端日榜（`o=mv_t` 实测恒空），而是由月榜数据本地重排得到，
 * 因此不会为空 —— 详见 [com.jmread.core.model.RankTab]。
 */
private val DEFAULT_RANK_TYPE = RankTab.H24.value

/** 排行榜：新晋热榜 / 周榜 / 月榜（H24 / D7 / D30），档位可用性由探测决定 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(DEFAULT_RANK_TYPE) }
    // 乐观全集起步：探测结果回来前先按三档渲染，避免首屏无 tab
    var tabs by remember { mutableStateOf(RankTab.optimistic) }
    var comics by remember { mutableStateOf<List<ComicSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyGridState()

    /** 请求代际：连点日/周/月榜时旧响应不得覆盖新选择 */
    var loadGen by remember { mutableIntStateOf(0) }

    fun load(t: String) {
        val gen = ++loadGen
        loading = true
        error = null
        scope.launch {
            try {
                val result = JmRepository.rank(t)
                if (gen != loadGen) return@launch
                comics = result.items
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGen) error = e.message ?: "加载失败"
            } finally {
                if (gen == loadGen) loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(type) }

    // 档位可用性探测：异步，收敛后隐藏空档位；若当前档位被移除则切到首个可用档位
    LaunchedEffect(Unit) {
        val available = runCatching { JmRepository.availableRankTabs() }
            .getOrDefault(RankTab.optimistic)
        if (available.isEmpty()) return@LaunchedEffect
        tabs = available
        if (available.none { it.value == type }) {
            type = available.first().value
            load(type)
            listState.requestScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("排行榜") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { tab ->
                    FilterChip(
                        selected = type == tab.value,
                        onClick = {
                            type = tab.value
                            load(tab.value)
                            // 切榜即回顶：新数据替换后网格会按索引保留位置，需显式重置
                            listState.requestScrollToItem(0)
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
            if (error != null && comics.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (comics.isEmpty() && !loading) {
                // ComicGridView 自身没有空态处理，不拦这一层的话空列表会渲染成纯白区域。
                // 档位可用性已由探测过滤，正常不会走到这里；留作探测失败时的兜底。
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "该档位暂无数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "可切换上方其它档位",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = true,
                    listState = listState,
                    onLoadMore = {},
                    onComicClick = onComicClick,
                )
            }
        }
    }
}
