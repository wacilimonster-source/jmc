package com.jmread.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 首屏骨架屏：与真实网格同列数、同卡片比例，加载完成后不发生布局跳动。
 * 只用于「列表为空且首次加载」，翻页加载仍使用尾部转圈。
 */
@Composable
fun ComicGridSkeleton(
    columns: Int = 2,
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
) {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(itemCount) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .background(placeholder, RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .height(12.dp)
                        .background(placeholder, RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.48f)
                        .height(10.dp)
                        .background(placeholder, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

/** 全屏错误态：统一图标位置、文案与重试入口 */
@Composable
fun ErrorRetry(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryText: String = "重试",
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                text = message.ifBlank { "加载失败，请稍后重试" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) { Text(retryText) }
        }
    }
}

/** 有旧内容时的顶部轻量错误条；不遮挡已加载内容 */
@Composable
fun InlineErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 空态：文案 + 可选操作入口 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onAction) { Text(actionText) }
            }
        }
    }
}

/** 设置/详情页用的小骨架行，避免长页面首屏只显示「加载中」 */
@Composable
fun DetailSkeleton(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "detail-skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "detail-skeleton-alpha",
    )
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Column(modifier.padding(24.dp)) {
        Box(
            Modifier
                .width(140.dp)
                .height(20.dp)
                .background(placeholder, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(16.dp))
        repeat(5) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(placeholder, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}
