package com.jmread.ui.download

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jmread.core.download.DlStatus
import com.jmread.core.download.DownloadExporter
import com.jmread.core.download.DownloadManager
import com.jmread.core.download.TaskRuntime
import com.jmread.ui.common.AppSnackbar
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 下载管理页：总存储占用 / 实时速度 / 每任务进度 / 失败重试 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onComicClick: (comicId: String, order: Int) -> Unit = { _, _ -> },
) {
    val tasks by DownloadManager.tasks.collectAsState()
    // 显式订阅派生状态：总占用 / 总速度独立刷新，不依赖 tasks 的隐式读取
    val totalBytes by DownloadManager.totalBytesFlow.collectAsState()
    val totalSpeed by DownloadManager.totalSpeedFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportState by DownloadExporter.state.collectAsState()
    var pendingExport by rememberSaveable(stateSaver = PendingExportSaver) {
        mutableStateOf<PendingExport?>(null)
    }
    var partialExport by rememberSaveable(stateSaver = PendingExportSaver) {
        mutableStateOf<PendingExport?>(null)
    }
    var lastExport by rememberSaveable(stateSaver = PendingExportSaver) {
        mutableStateOf<PendingExport?>(null)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri != null && target != null) {
            lastExport = target
            DownloadExporter.start(context, target.comicId, uri)
        }
    }

    fun launchExport(target: PendingExport) {
        pendingExport = target
        exportLauncher.launch(target.fileName)
    }

    // 导出终态：Snackbar 给分享/重试动作；消费后不再重复弹
    LaunchedEffect(exportState) {
        when (val state = exportState) {
            is DownloadExporter.ExportState.Done -> {
                val result = AppSnackbar.hostState.showSnackbar(
                    message = "已导出 ${state.fileName}",
                    actionLabel = "分享",
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    shareZip(context, state.uri)
                }
                DownloadExporter.consume()
            }
            is DownloadExporter.ExportState.Error -> {
                val result = AppSnackbar.hostState.showSnackbar(
                    message = state.message,
                    actionLabel = if (lastExport != null) "重试" else null,
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    lastExport?.let { launchExport(it) }
                }
                DownloadExporter.consume()
            }
            else -> {}
        }
    }

    // 失败提示以进入页面时的状态为基线，避免恢复清单的批量 FAILED 一进页就轰炸。
    // key 用失败集合而不是整个 tasks：进度每秒更新时不会取消正在显示的 snackbar。
    var failuresInitialized by remember { mutableStateOf(false) }
    var knownFailures by remember { mutableStateOf<Set<String>>(emptySet()) }
    val failedKeys = remember(tasks) {
        tasks.filter { it.status == DlStatus.FAILED }.map { it.key }.toSet()
    }
    LaunchedEffect(failedKeys) {
        val current = failedKeys
        if (!failuresInitialized) {
            if (tasks.isNotEmpty()) {
                knownFailures = current
                failuresInitialized = true
            }
            return@LaunchedEffect
        }
        val newOnes = current - knownFailures
        knownFailures = current
        if (newOnes.isNotEmpty()) {
            val result = AppSnackbar.hostState.showSnackbar(
                message = if (newOnes.size == 1) "下载失败" else "${newOnes.size} 个任务下载失败",
                actionLabel = "重试全部",
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                newOnes.forEach { DownloadManager.retry(it) }
            }
        }
    }

    if (partialExport != null) {
        val target = partialExport!!
        AlertDialog(
            onDismissRequest = { partialExport = null },
            title = { Text("导出《${target.title}》") },
            text = {
                Text(
                    "还有 ${target.incomplete} 章未完成，这次只导出已完成的 ${target.completed} 章？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    partialExport = null
                    launchExport(target)
                }) { Text("导出已完成") }
            },
            dismissButton = {
                TextButton(onClick = { partialExport = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 总览
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已占用 ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${tasks.count { it.isFinished }}/${tasks.size} 个任务完成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val speed = totalSpeed
                    if (speed > 0) {
                        Text(
                            text = "↓ ${formatBytes(speed)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            (exportState as? DownloadExporter.ExportState.Running)?.let { running ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "正在导出 ${running.chapterIndex}/${running.totalChapters} 章",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = running.chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (running.totalChapters <= 0) 0f
                                    else running.chapterIndex.toFloat() / running.totalChapters
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(onClick = { DownloadExporter.cancel() }) { Text("取消") }
                    }
                }
            }
            val waitingWifi = tasks.any { it.waitingForWifi }
            val waitingNetwork = tasks.any { it.waitingForNetwork }
            if (waitingWifi || waitingNetwork) {
                Text(
                    text = if (waitingWifi) {
                        "移动网络下已暂停，连接可用 Wi-Fi 后自动继续"
                    } else {
                        "无网络连接，恢复后自动继续"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 展开态上提到分支外：此前 remember 位于 else 分支内，
            // tasks 在"空 ↔ 非空"之间切换时会被重置，展开状态丢失
            var expandedComic by remember { mutableStateOf<Set<String>>(emptySet()) }

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "暂无下载任务\n在漫画详情页点击下载按钮即可开始",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // 按漫画分组：漫画头卡片 + 展开的章节行
                val grouped = tasks
                    .groupBy { it.task.comicId }
                    .entries
                    .sortedByDescending { it.value.maxOfOrNull { t -> t.task.createdAt } ?: 0L }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(grouped, key = { it.key }) { (comicId, comicTasks) ->
                        val expanded = comicId in expandedComic
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        ComicGroupCard(
                            comicId = comicId,
                            tasks = comicTasks,
                            expanded = expanded,
                            onToggle = {
                                expandedComic = if (expanded) {
                                    expandedComic - comicId
                                } else {
                                    expandedComic + comicId
                                }
                            },
                            onChapterClick = { order ->
                                if (comicTasks.any { t -> t.isFinished && t.task.order == order }) {
                                    onComicClick(comicId, order)
                                }
                            },
                            onRetry = { DownloadManager.retry(it) },
                            onDelete = { DownloadManager.remove(it) },
                            onDeleteAll = { comicTasks.forEach { t -> DownloadManager.remove(t.key) } },
                            exportEnabled = exportState !is DownloadExporter.ExportState.Running,
                            deleteEnabled = (exportState as? DownloadExporter.ExportState.Running)?.comicId != comicId,
                            onExport = {
                                val target = PendingExport(
                                    comicId = comicId,
                                    title = comicTasks.firstOrNull()?.task?.comicTitle.orEmpty(),
                                    incomplete = comicTasks.count { !it.isFinished },
                                    completed = comicTasks.count { it.isFinished },
                                )
                                if (target.incomplete > 0) partialExport = target
                                else launchExport(target)
                            },
                        )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicGroupCard(
    comicId: String,
    tasks: List<TaskRuntime>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean = true,
    deleteEnabled: Boolean = true,
) {
    val first = tasks.first()
    val task = first.task
    val done = tasks.count { it.isFinished }
    val downloading = tasks.count { it.status == DlStatus.DOWNLOADING || it.status == DlStatus.PENDING }
    val bytes = tasks.sumOf { it.totalBytes }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = task.coverUrl,
                contentDescription = task.comicTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.comicTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("$done/${tasks.size} 章完成")
                        if (downloading > 0) append(" · $downloading 章下载中")
                        append(" · ${formatBytes(bytes)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            // 展开/收起仅留一枚图标；导出与删除降为次级操作（展开区底部）
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(Modifier.animateContentSize()) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                tasks.sortedBy { it.task.order }.forEach { runtime ->
                    DownloadTaskRow(
                        runtime = runtime,
                        onClick = { onChapterClick(runtime.task.order) },
                        onRetry = { onRetry(runtime.key) },
                        onDelete = { onDelete(runtime.key) },
                        deleteEnabled = deleteEnabled,
                    )
                }
                // 次级操作行：导出/删除不与进度争视觉焦点；禁用条件与原实现一致
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    TextButton(onClick = onExport, enabled = exportEnabled && done > 0) {
                        Text(
                            "导出 ZIP",
                            color = if (exportEnabled && done > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDeleteAll, enabled = deleteEnabled) {
                        Text(
                            "删除整本",
                            color = if (deleteEnabled) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    runtime: TaskRuntime,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
) {
    val task = runtime.task
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.epTitle.ifBlank { "第 ${task.order} 话" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (runtime.waitingForWifi || runtime.waitingForNetwork) {
                Text(
                    text = if (runtime.waitingForWifi) "等待 Wi-Fi" else "等待网络",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${runtime.downloadedPages}/${task.pageCount} 页 · ${formatBytes(runtime.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else when (runtime.status) {
                DlStatus.DOWNLOADING, DlStatus.PENDING -> {
                    LinearProgressIndicator(
                        progress = { runtime.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("${runtime.progress}% · ${runtime.downloadedPages}/${task.pageCount} 页")
                            append(" · ${formatBytes(runtime.totalBytes)}")
                            if (runtime.bytesPerSecond > 0) {
                                append(" · ${formatBytes(runtime.bytesPerSecond)}/s")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DlStatus.COMPLETED -> {
                    Text(
                        text = "已完成 · ${task.pageCount} 页 · ${formatBytes(runtime.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                DlStatus.FAILED -> {
                    Text(
                        text = "失败：${runtime.error.ifBlank { "网络错误" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                else -> {}
            }
        }
        if (runtime.status == DlStatus.FAILED) {
            IconButton(onClick = onRetry) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "重试",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onDelete, enabled = deleteEnabled) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

/** 人类可读体积 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/** 一次导出意图：冻结漫画与完成/未完成章数。 */
private data class PendingExport(
    val comicId: String,
    val title: String,
    val incomplete: Int,
    val completed: Int,
) {
    val fileName: String
        get() {
            val safe = title.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").trim()
                .ifBlank { "JM Reader" }
            return "${safe.take(60)}.zip"
        }
}

/** SAF 选择器打开期间 Activity 可能被重建；导出意图必须可保存，否则选完目录静默丢失。 */
private val PendingExportSaver: Saver<PendingExport?, Any> = Saver(
    save = { value ->
        value?.let { listOf(it.comicId, it.title, it.incomplete, it.completed) } ?: emptyList<Any>()
    },
    restore = { raw ->
        val list = raw as? List<*>
        if (list == null || list.size < 4) {
            null
        } else {
            val comicId = list[0] as? String
            val title = list[1] as? String
            val incomplete = list[2] as? Int
            val completed = list[3] as? Int
            if (comicId != null && title != null && incomplete != null && completed != null) {
                PendingExport(comicId, title, incomplete, completed)
            } else {
                null
            }
        }
    },
)

private fun shareZip(context: android.content.Context, uri: Uri) {
    val readable = runCatching {
        context.contentResolver.openInputStream(uri)?.use { }
    }.isSuccess
    if (!readable) {
        Toast.makeText(context, "导出文件不可读取，请从文件管理器分享", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享导出文件"))
    }
}
