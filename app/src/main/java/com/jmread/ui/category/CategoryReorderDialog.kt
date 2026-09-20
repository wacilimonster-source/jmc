package com.jmread.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.jmread.core.model.ComicCategory
import kotlin.math.roundToInt

@Composable
fun CategoryReorderDialog(
    categories: List<ComicCategory>,
    currentSettings: com.jmread.data.CategorySettings.Settings,
    onSettingsChange: (com.jmread.data.CategorySettings.Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember(categories, currentSettings) {
        val orderMap = currentSettings.order.withIndex().associate { (i, id) -> id to i }
        categories.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
    }
    // 使用 Pair(id, title) 便于显示。
    // key 必须包含 categories/currentSettings：此前只在首次组合时初始化，
    // 若对话框打开时分类尚未加载完成，会永久停留在空列表。
    var order by remember(categories, currentSettings) {
        mutableStateOf(items.map { it.id to it.title })
    }
    var hidden by remember(currentSettings) { mutableStateOf(currentSettings.hidden) }
    // 追踪正在拖拽的项的 ID
    var draggedId by remember { mutableStateOf<String?>(null) }
    // 拖拽偏移量（像素）
    var dragOffsetY by remember { mutableStateOf(0f) }
    // 当前悬浮的目标索引
    var hoverIndex by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类排序与显示") },
        text = {
            Column {
                Text(
                    text = "长按拖拽排序，点击眼睛切换显示/隐藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    // 提供稳定 key（分类 id）：否则拖拽重排后列表项状态会错位
                    items(order.size, key = { index -> order[index].first }) { index ->
                        val (catId, catTitle) = order[index]
                        val isHidden = catId in hidden
                        val isDragged = draggedId == catId
                        val isHover = hoverIndex == index && draggedId != null && draggedId != catId

                        val bgColor by animateColorAsState(
                            targetValue = when {
                                isDragged -> MaterialTheme.colorScheme.primaryContainer
                                isHover -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                isHidden -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                            },
                            label = "bg",
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .graphicsLayer {
                                    if (isDragged) {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                        shadowElevation = 8f
                                        translationY = dragOffsetY
                                    }
                                }
                                .background(bgColor)
                                .pointerInput(catId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedId = catId
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            // 执行交换
                                            val fromId = draggedId
                                            val toIdx = hoverIndex
                                            if (fromId != null && toIdx != null) {
                                                val fromIdx = order.indexOfFirst { it.first == fromId }
                                                if (fromIdx >= 0 && fromIdx != toIdx) {
                                                    val mutable = order.toMutableList()
                                                    val item = mutable.removeAt(fromIdx)
                                                    mutable.add(toIdx, item)
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
                                            // 计算当前拖拽到的目标位置
                                            val currentId = draggedId ?: return@detectDragGesturesAfterLongPress
                                            val currentIdx = order.indexOfFirst { it.first == currentId }
                                            if (currentIdx < 0) return@detectDragGesturesAfterLongPress
                                            val itemHeight = 48.dp.toPx()
                                            val targetIdx = (currentIdx + (dragOffsetY / itemHeight).roundToInt())
                                                .coerceIn(0, order.size - 1)
                                            hoverIndex = targetIdx
                                        },
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "拖拽",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = catTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                imageVector = if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (isHidden) "隐藏" else "显示",
                                tint = if (isHidden) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        hidden = if (catId in hidden) hidden - catId else hidden + catId
                                    },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSettingsChange(
                    com.jmread.data.CategorySettings.Settings(
                        order = order.map { it.first },
                        hidden = hidden,
                    )
                )
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
