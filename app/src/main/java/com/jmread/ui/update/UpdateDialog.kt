package com.jmread.ui.update

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jmread.core.update.UpdateManager

/**
 * 版本更新对话框：显示更新说明 + 下载进度 + 安装。
 * 供首页更新横幅 / 设置页复用。
 *
 * 下载态一律读 [UpdateManager.downloadUi]（app 级状态机）：宿主页面销毁不再
 * 静默取消下载，离开后点横幅重开进度仍在；与设置页共用同一条下载流，
 * 杜绝两个协程并发写同一个 APK 文件。
 */
@Composable
fun UpdateDialog(
    info: UpdateManager.UpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dlState by UpdateManager.downloadUi.collectAsState()
    // 仅装本地交互错误（安装包丢失/安装失败），下载错误在 dlState.Failed 里
    var installError by remember { mutableStateOf<String?>(null) }

    val downloading = dlState is UpdateManager.DownloadUi.Downloading
    val downloadingProgress = dlState as? UpdateManager.DownloadUi.Downloading

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("发现新版本 v${info.version}") },
        text = {
            Column {
                Text(
                    text = info.notes.ifBlank { "修复已知问题，提升体验" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (downloadingProgress != null) {
                    Spacer(Modifier.height(12.dp))
                    if (downloadingProgress.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { downloadingProgress.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${(downloadingProgress.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 总长未知：按已下载字节数展示
                        Text(
                            text = "已下载 %.1f MB…".format(downloadingProgress.downloadedBytes / 1024f / 1024f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                (dlState as? UpdateManager.DownloadUi.Failed)?.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "下载失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                installError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (val d = dlState) {
                is UpdateManager.DownloadUi.Done -> Button(onClick = {
                    when {
                        !d.apk.exists() ->
                            installError = "安装包丢失，请重新下载"
                        !UpdateManager.install(context, d.apk) ->
                            installError = "安装失败，请手动打开 APK"
                        else -> onDismiss()
                    }
                }) { Text("立即安装") }

                is UpdateManager.DownloadUi.Downloading ->
                    TextButton(onClick = {}, enabled = false) { Text("下载中…") }

                // Idle / Failed：可发起（重试）下载。autoInstall=false 保留本入口
                // "手动点安装"的原有交互
                else -> Button(onClick = {
                    installError = null
                    UpdateManager.startDownload(context, info, autoInstall = false)
                }) { Text("下载") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!downloading) onDismiss() }) {
                Text(if (dlState is UpdateManager.DownloadUi.Done) "稍后安装" else "取消")
            }
        },
    )
}
