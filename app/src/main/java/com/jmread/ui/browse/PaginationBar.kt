package com.jmread.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 页码分页条：左箭头 | 页码（居中块状）| 右箭头 | 跳页按钮
 * 数字在矩形块内居中；点跳页弹窗输入页码直达。
 *
 * progressMode = true：筛选激活时的加载进度模式——页码可点范围为已加载页
 * （loadedPages），后台每加载完一页自动扩展范围；加载未完成时右箭头尾部显示
 * "x / N 页"进度 + 转圈，全部加载完切换回完整跳页按钮（跳页为客户端切片）。
 */
@Composable
fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    progressMode: Boolean = false,
    loadedPages: Int = totalPages,
) {
    if (totalPages <= 1) return
    var showJump by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }

    val loaded = if (progressMode) loadedPages.coerceIn(1, totalPages) else totalPages

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onPageChange((currentPage - 1).coerceAtLeast(1)) },
            enabled = currentPage > 1,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一页")
        }
        // 页码组：权重 1f 居中，占满中间区域
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pageWindow(currentPage, loaded).forEach { p ->
                if (p == null) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Surface(
                        onClick = { onPageChange(p) },
                        shape = MaterialTheme.shapes.small,
                        color = if (p == currentPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$p",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (p == currentPage) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        IconButton(
            onClick = { onPageChange((currentPage + 1).coerceAtMost(loaded)) },
            enabled = currentPage < loaded,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一页")
        }
        // 尾部：进度模式加载中显示进度 + 转圈，否则显示跳页按钮
        if (progressMode && loaded < totalPages) {
            Row(
                modifier = Modifier.width(88.dp).height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "$loaded/$totalPages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(12.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            // 跳页按钮（弹窗输入页码）
            Surface(
                onClick = {
                    jumpInput = ""
                    showJump = true
                },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.width(64.dp).height(36.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.FilterCenterFocus,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "跳页",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }

    if (showJump) {
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("跳转到指定页") },
            text = {
                OutlinedTextField(
                    value = jumpInput,
                    onValueChange = { jumpInput = it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text("页码（1-$totalPages）") },
                    placeholder = { Text("当前第 $currentPage 页") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val p = jumpInput.toIntOrNull()
                        if (p != null && p in 1..totalPages) {
                            onPageChange(p)
                            showJump = false
                        }
                    },
                    enabled = jumpInput.toIntOrNull()?.let { it in 1..totalPages } == true,
                ) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { showJump = false }) { Text("取消") }
            },
        )
    }
}

/** 生成页码窗口：当前页前后各 1 页 + 首尾页，间隔>1 处插入省略号(null) */
private fun pageWindow(current: Int, total: Int): List<Int?> {
    if (total <= 7) return (1..total).toList()
    val pages = listOf(1, current - 1, current, current + 1, total)
        .filter { it in 1..total }
        .distinct()
        .sorted()
    val result = mutableListOf<Int?>()
    var prev = 0
    for (p in pages) {
        if (p - prev > 1) result.add(null)
        result.add(p)
        prev = p
    }
    return result
}