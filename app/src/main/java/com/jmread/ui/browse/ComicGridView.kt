package com.jmread.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jmread.core.model.ComicSummary

/** 漫画网格：每行数量随设置（2/3，默认 2）+ 触底自动加载更多 */
@Composable
fun ComicGridView(
    comics: List<ComicSummary>,
    loading: Boolean = false,
    endReached: Boolean = false,
    listState: LazyGridState,
    onLoadMore: () -> Unit,
    onComicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 是否显示尾部加载转圈（与下拉刷新指示器重叠的页面传 false，避免双转圈） */
    showTailLoading: Boolean = true,
) {
    val statusVersion by com.jmread.data.ReaderStatus.version.collectAsState()
    val columns by com.jmread.data.GridSettings.columnsFlow.collectAsState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(comics, key = { it.ref }) { comic ->
            val readStatus = remember(comic.ref, statusVersion) { com.jmread.data.ReaderStatus.of(comic.ref) }
            ComicCard(
                comic = comic,
                readStatus = readStatus,
                largeTitle = columns == 2,
                onClick = { onComicClick(comic.ref) },
            )
        }
        if (loading && showTailLoading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                }
            }
        }
    }

    // 触底预加载提前量保持约 2~3 行：2 列提前 6 个，3 列提前 4 个
    val preloadAhead = if (columns == 2) 6 else 4
    LaunchedEffect(listState, endReached, loading, columns) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - preloadAhead
        }.collect { shouldLoad ->
            if (shouldLoad && !loading && !endReached) {
                onLoadMore()
            }
        }
    }
}

@Composable
private fun ComicCard(
    comic: ComicSummary,
    readStatus: com.jmread.data.ReadStatus?,
    /** 2 列大卡片时标题用更大字号 */
    largeTitle: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            comic.coverUrl?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (comic.coverUrl.isNullOrBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = comic.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 阅读状态角标：右上角；读完优先显示
            when (readStatus) {
                com.jmread.data.ReadStatus.FINISHED -> ReadBadge(
                    text = "读完",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                com.jmread.data.ReadStatus.READ -> ReadBadge(
                    text = "已读",
                    container = Color(0x99000000),
                    content = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                null -> {}
            }
            // 云端收藏的「有更新」角标：左上角（数据来自 /favorite.latest_ep，仅收藏列表填充）
            if (comic.latestEpText.isNotBlank()) {
                ReadBadge(
                    text = "有更新",
                    container = MaterialTheme.colorScheme.tertiary,
                    content = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        Text(
            text = comic.title,
            style = if (largeTitle) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodySmall
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (comic.author.isNotBlank()) {
            Text(
                text = comic.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 1.dp),
        ) {
            if (comic.updatedAt.isNotBlank()) {
                Text(
                    text = comic.updatedAt.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (comic.totalLikes > 0) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    text = "${comic.totalLikes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

/** 封面右上角阅读状态角标 */
@Composable
private fun ReadBadge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = modifier
            .padding(4.dp)
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
