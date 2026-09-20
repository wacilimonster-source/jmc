package com.jmread.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.jmread.core.netconfig.DomainPool
import com.jmread.core.scramble.ScrambleCache
import com.jmread.core.update.UpdateManager
import com.jmread.data.GridSettings
import com.jmread.data.ReaderPrefs
import com.jmread.data.SourcePrefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** 从"我的"页进入时为 push 页面，显示返回按钮 */
    onBack: (() -> Unit)? = null,
    onOpenLog: () -> Unit = {},
) {
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader
        .collectAsState(initial = true)
    val gridColumns by GridSettings.columnsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var readerMode by remember { mutableStateOf(ReaderPrefs.current().readerMode) }
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 网络 ────────────────────────────────────────────────
            var manualHost by remember { mutableStateOf(SourcePrefs.current().jmBaseUrl.orEmpty()) }
            var currentHost by remember { mutableStateOf(DomainPool.currentApiHost) }
            SettingsGroup(
                header = "网络",
                supporting = "默认自动选择线路：内置镜像 + 服务端下发，失败自动切换",
            ) {
                ListItem(
                    headlineContent = { Text("当前生效域名") },
                    supportingContent = { Text(if (manualHost.isBlank()) "$currentHost（自动）" else "$currentHost（手动）") },
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("手动指定 API 域名") },
                    supportingContent = { Text("留空保持自动；换线路失败时可手动填写镜像域名") },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch {
                                SourcePrefs.current().setJmBaseUrl(manualHost.takeIf { it.isNotBlank() })
                                currentHost = DomainPool.currentApiHost
                            }
                        }) { Text("保存") }
                    },
                )
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    singleLine = true,
                    placeholder = { Text("例如 www.cdngwc.cc（留空=自动）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("图片乱序还原统计") },
                    supportingContent = { Text(ScrambleCache.statsText()) },
                )
            }

            // ── 浏览 ────────────────────────────────────────────────
            SettingsGroup(header = "浏览") {
                Column {
                    ListItem(
                        headlineContent = { Text("作品列表每行数量") },
                        supportingContent = {
                            Text("2 个封面更大，3 个一屏浏览更多；切换后即时生效")
                        },
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        SegmentedButton(
                            selected = gridColumns == 2,
                            onClick = { GridSettings.setColumns(2) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("2 个") }
                        SegmentedButton(
                            selected = gridColumns == 3,
                            onClick = { GridSettings.setColumns(3) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("3 个") }
                    }
                }
            }

            // ── 阅读 ────────────────────────────────────────────────
            SettingsGroup(header = "阅读") {
                Column {
                    ListItem(
                        headlineContent = { Text("默认阅读模式") },
                        supportingContent = {
                            Text("滚动流适合条漫，横滑翻页适合页漫；阅读页内可随时切换")
                        },
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        SegmentedButton(
                            selected = readerMode == 0,
                            onClick = {
                                readerMode = 0
                                ReaderPrefs.current().readerMode = 0
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("滚动流") }
                        SegmentedButton(
                            selected = readerMode == 1,
                            onClick = {
                                readerMode = 1
                                ReaderPrefs.current().readerMode = 1
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("横滑翻页") }
                    }
                }
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("阅读时隐藏底部导航栏") },
                    supportingContent = {
                        Text("看漫画时隐藏底部标签栏，画面向下延伸至屏幕底边")
                    },
                    trailingContent = {
                        Switch(
                            checked = hideBottomBarInReader,
                            onCheckedChange = {
                                scope.launch { ReaderPrefs.current().setHideBottomBarInReader(it) }
                            },
                        )
                    },
                )
            }

            // ── 通用 ────────────────────────────────────────────────
            SettingsGroup(header = "通用") {
                UpdateSection()
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("调试日志") },
                    supportingContent = { Text("查看应用运行日志") },
                    trailingContent = { ChevronIcon() },
                    modifier = Modifier.clickable(onClick = onOpenLog),
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("关于 JM Reader") },
                    supportingContent = { Text("版本 ${UpdateManager.currentVersionName}") },
                    trailingContent = { ChevronIcon() },
                    modifier = Modifier.clickable { showAbout = true },
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** 通用组 · 检查更新行（弹窗流程与原实现一致） */
@Composable
private fun UpdateSection() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 检查结果留在本地（检查很快，随页面销毁无所谓）；下载态以 UpdateManager 为准
    // （app 级作用域）：下载中关对话框/离开设置页都不再静默取消，回来进度仍可见、可重试
    var checkState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var lastFound by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    val dlState by UpdateManager.downloadUi.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }
    val state: UpdateUiState = when (val d = dlState) {
        is UpdateManager.DownloadUi.Downloading -> UpdateUiState.Downloading
        is UpdateManager.DownloadUi.Done -> UpdateUiState.Downloaded
        is UpdateManager.DownloadUi.Failed -> UpdateUiState.DownloadFailed(d.message)
        UpdateManager.DownloadUi.Idle -> checkState
    }
    val progress = (dlState as? UpdateManager.DownloadUi.Downloading)?.progress ?: 0f

    ListItem(
        headlineContent = { Text("检查更新") },
        supportingContent = { Text("发现新版本时提示下载安装") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == UpdateUiState.Checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "v${UpdateManager.currentVersionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
            modifier = Modifier
                .clickable {
                    if (checkState == UpdateUiState.Checking) return@clickable
                    scope.launch {
                        checkState = UpdateUiState.Checking
                        dialogOpen = true
                        val result = UpdateManager.checkResult()
                        // 上一轮已完成的下载状态让位给新一轮检查结果（下载中则内部忽略，后台继续）
                        UpdateManager.resetDownload()
                        checkState = when (result) {
                            is UpdateManager.CheckResult.Available -> {
                                lastFound = result.info
                                UpdateUiState.Found(result.info)
                            }
                            UpdateManager.CheckResult.UpToDate -> UpdateUiState.UpToDate
                            is UpdateManager.CheckResult.Failed -> UpdateUiState.Error
                        }
                    }
                },
    )

    if (dialogOpen) {
        AlertDialog(
            // 下载已移到 UpdateManager 的 app 级作用域：随时可关，
            // 下载在后台继续，重开对话框进度仍可见（与"进度仍可见"的一致行为）
            onDismissRequest = { dialogOpen = false },
            title = {
                Text(
                    when (state) {
                        is UpdateUiState.Found -> "发现新版本 ${(state as UpdateUiState.Found).info.version}"
                        UpdateUiState.Checking -> "检查更新"
                        UpdateUiState.Downloading -> "下载更新"
                        UpdateUiState.Downloaded -> "下载完成"
                        is UpdateUiState.DownloadFailed -> "下载失败"
                        UpdateUiState.UpToDate -> "已是最新版本"
                        UpdateUiState.Error -> "检查失败"
                        UpdateUiState.Idle -> "更新"
                    }
                )
            },
            text = {
                when (state) {
                    UpdateUiState.Checking -> Text("正在检查…")
                    UpdateUiState.UpToDate -> Text(
                        "当前版本 ${UpdateManager.currentVersionName} 已是最新。"
                    )
                    is UpdateUiState.Found -> {
                        val info = (state as UpdateUiState.Found).info
                        Column {
                            Text("新版本：${info.version}")
                            if (info.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    info.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    UpdateUiState.Downloading -> Column {
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(progress * 100).toInt()}%")
                        } else {
                            Text("下载中…（总大小未知）")
                        }
                    }
                    UpdateUiState.Downloaded -> Text("APK 已下载，点击安装完成更新。")
                    is UpdateUiState.DownloadFailed -> Text("${state.message}，可重试。")
                    UpdateUiState.Error -> Text("网络异常或服务器未就绪，请稍后重试。")
                    UpdateUiState.Idle -> Text("")
                }
            },
            confirmButton = {
                when (state) {
                    is UpdateUiState.Found -> {
                        val info = (state as UpdateUiState.Found).info
                        androidx.compose.material3.Button(onClick = {
                            // 下载跑在 UpdateManager 的 app 级作用域，页面销毁不影响
                            UpdateManager.startDownload(context, info)
                        }) { Text("下载") }
                    }
                    is UpdateUiState.DownloadFailed -> {
                        androidx.compose.material3.Button(onClick = {
                            lastFound?.let { UpdateManager.startDownload(context, it) }
                        }) { Text("重试") }
                    }
                    UpdateUiState.Downloaded -> {
                        val apk = (dlState as? UpdateManager.DownloadUi.Done)?.apk
                        if (apk != null) {
                            androidx.compose.material3.Button(onClick = {
                                UpdateManager.install(context, apk)
                            }) { Text("安装") }
                        } else {
                            TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                        }
                    }
                    UpdateUiState.Downloading -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }
                    else -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                    }
                }
            },
            dismissButton = {
                when {
                    checkState is UpdateUiState.Found || checkState == UpdateUiState.Checking -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("取消") }
                    }
                    state == UpdateUiState.Downloading -> {
                        // 下载转入后台继续：对话框可关，进度在 UpdateManager 里不丢
                        TextButton(onClick = { dialogOpen = false }) { Text("后台运行") }
                    }
                    else -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                    }
                }
            },
        )
    }
}

/** 关于对话框：版本、简介与内容来源声明 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 JM Reader") },
        text = {
            Column {
                Text(
                    "JM Reader · 禁漫阅读器",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本 ${UpdateManager.currentVersionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "仅含禁漫数据源的漫画阅读器，支持分类/标签浏览、四维搜索、排行榜、每周必看、评论、下载与本地阅读进度。浏览与阅读免登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "本应用仅提供聚合与阅读功能，所有内容均来自第三方站点，版权归原作者及对应站点所有。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 更新弹窗状态 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Found(val info: UpdateManager.UpdateInfo) : UpdateUiState
    data object Downloading : UpdateUiState
    data object Downloaded : UpdateUiState
    /** 下载失败（区别于检查失败）：保留服务端原因文案，可重试 */
    data class DownloadFailed(val message: String) : UpdateUiState
    data object Error : UpdateUiState
}
