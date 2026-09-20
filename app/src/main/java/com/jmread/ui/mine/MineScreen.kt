package com.jmread.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.data.SecureAccountStore
import com.jmread.data.SourcePrefs
import kotlinx.coroutines.launch

/**
 * 我的：未登录 = 纯本地中心（书架/最近/下载/已读/设置全部可用）；
 * 已登录 = 增加账号卡与云端区块（云端能力位验证后放开）。
 */
@Composable
fun MineScreen(
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenAuthorFavourites: () -> Unit = {},
    onOpenFollowManage: () -> Unit = {},
    onOpenReader: (String, Int) -> Unit = { _, _ -> },
    onOpenLogin: () -> Unit = {},
    onOpenRecentReads: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(JmRepository.isLoggedIn) }
    var account by remember { mutableStateOf(JmRepository.accountName) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var deleteSavedCredentials by remember { mutableStateOf(false) }
    var dailyMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "JM Reader",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = if (loggedIn) "已登录 · ${account ?: "账号"}" else "未登录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (loggedIn) "云端能力已解锁" else "浏览 / 搜索 / 阅读 / 下载无需登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!loggedIn) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "登录",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onOpenLogin),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // 每日签到（禁漫独有）：能力位验证后放开（hasDailyCheckIn）
        if (loggedIn && JmCapabilities.hasDailyCheckIn) {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                dailyMsg = try {
                                    val r = JmRepository.dailyCheckIn()
                                    if (r.checkedIn) "今日已签到 · 连续 ${r.consecutiveDays} 天"
                                    else "签到成功 · 连续 ${r.consecutiveDays} 天"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    e.message ?: "签到失败"
                                }
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "每日签到",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (dailyMsg != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = dailyMsg ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        MenuRow("本地书架", onClick = onOpenBookmarks)
        MenuRow("收藏的作者", onClick = onOpenAuthorFavourites)
        MenuRow("关注管理", onClick = onOpenFollowManage)
        MenuRow("阅读历史", onClick = onOpenRecentReads)
        MenuRow("下载", onClick = onOpenDownloads)
        MenuRow("设置", onClick = onOpenSettings)
        if (loggedIn) {
            MenuRow("退出登录") {
                showLogoutDialog = true
            }
        }
    }

    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = {
                Column {
                    Text("确定退出当前账号吗？")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = deleteSavedCredentials,
                            onCheckedChange = { deleteSavedCredentials = it },
                        )
                        Text(
                            "同时删除保存的账号密码",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLogoutDialog = false
                    scope.launch {
                        JmRepository.logout()
                        if (deleteSavedCredentials) SecureAccountStore.clear()
                        loggedIn = false
                        account = null
                    }
                }) { Text("退出") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
    HorizontalDivider()
}
