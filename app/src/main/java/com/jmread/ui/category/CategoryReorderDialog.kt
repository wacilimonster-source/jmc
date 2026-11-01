package com.jmread.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jmread.core.model.ComicCategory
import com.jmread.data.CategorySettings
import com.jmread.data.orderedCategories
import kotlin.math.roundToInt

/**
 * 分类排序与显示（P1-06）：长按拖拽排序 + 眼睛切换显示/隐藏 + 恢复默认。
 *
 * - 使用实测行高计算拖拽目标，字体放大时不会错位；
 * - 每行提供“上移/下移”无障碍自定义操作；
 * - 至少保留 1 个可见分类，全隐藏时保存禁用。
 */
@Composable
fun CategoryReorderDialog(
    categories: List<ComicCategory>,
    initial: CategorySettings.Settings,
    onDismiss: () -> Unit,
    onSave: (CategorySettings.Settings) -> Unit,
) {
    var order by remember(categories, initial) {
        mutableStateOf(orderedCategories(categories, initial))
    }
    var hidden by remember(initial) { mutableStateOf(initial.hidden) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val fallbackRowHeightPx = with(density) { 48.dp.toPx() }
    val visibleCount = order.count { it.id !in hidden }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类排序与显示") },
        text = {
            Column {
                Text(
                    text = "长按拖拽排序，点击眼睛切换显示/隐藏；至少保留一个分类",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(top = 8.dp),
                ) {
                    items(order.size, key = { index -> order[index].id }) { index ->
                        val category = order[index]
                        val isHidden = category.id in hidden
                        val isDragged = draggedId == category.id
                        val isHover = hoverIndex == index && draggedId != null && draggedId != category.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .graphicsLayer {
                                    if (isDragged) {
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                        translationY = dragOffsetY
                                        shadowElevation = 8f
                                    }
                                }
                                .background(
                                    when {
                                        isDragged || isHover -> MaterialTheme.colorScheme.primaryContainer
                                        isHidden -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                    }
                                )
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction("上移") {
                                            if (index > 0) {
                                                val mutable = order.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index - 1, item)
                                                order = mutable
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        CustomAccessibilityAction("下移") {
                                            if (index < order.lastIndex) {
                                                val mutable = order.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index + 1, item)
                                                order = mutable
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                    )
                                }
                                .pointerInput(category.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedId = category.id
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            val fromId = draggedId
                                            val toIndex = hoverIndex
                                            if (fromId != null && toIndex != null) {
                                                val fromIndex = order.indexOfFirst { it.id == fromId }
                                                if (fromIndex >= 0 && fromIndex != toIndex) {
                                                    val mutable = order.toMutableList()
                                                    val item = mutable.removeAt(fromIndex)
                                                    mutable.add(toIndex.coerceIn(0, mutable.size), item)
                                                    order = mutable
                                                }
                                            }
                                            draggedId = null
                                            dragOffsetY = 0f
                                            hoverIndex = null
                                        },
                                        onDragCancel = {
                                            draggedId = null
                                            dragOffsetY = 0f
                                            hoverIndex = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val currentId = draggedId ?: return@detectDragGesturesAfterLongPress
                                            val currentIndex = order.indexOfFirst { it.id == currentId }
                                            if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                            val itemHeight = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == currentId }
                                                ?.size
                                                ?.toFloat()
                                                ?.takeIf { it > 0f }
                                                ?: fallbackRowHeightPx
                                            val target = (currentIndex + (dragOffsetY / itemHeight).roundToInt())
                                                .coerceIn(0, order.size - 1)
                                            hoverIndex = target
                                        },
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "拖拽排序",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isHidden) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (isHidden) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (isHidden) "显示" else "隐藏",
                                tint = if (isHidden) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        hidden = if (isHidden) hidden - category.id else hidden + category.id
                                    },
                            )
                        }
                    }
                }
                if (visibleCount == 0) {
                    Text(
                        text = "至少保留一个可见分类",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = visibleCount > 0,
                onClick = {
                    onSave(
                        CategorySettings.Settings(
                            order = order.map { it.id },
                            hidden = hidden,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    order = categories
                    hidden = emptySet()
                }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
