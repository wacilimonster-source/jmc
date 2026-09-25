package com.jmread.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.data.SecureAccountStore
import com.jmread.network.JmDailyCalendar
import kotlinx.coroutines.launch

/**
 * 我的：未登录 = 纯本地中心（书架/最近/下载/已读/设置全部可用）；
 * 已登录 = 增加签到月历卡与云端区块（云端收藏/云端历史）。
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
    onOpenCloudFavourites: () -> Unit = {},
    onOpenCloudHistory: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(JmRepository.isLoggedIn) }
    var account by remember { mutableStateOf(JmRepository.accountName) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var deleteSavedCredentials by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize()) {
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
        // 每日签到月历卡（登录后显示；数据/执行 2026-09-25 实测打通）
        if (loggedIn && JmCapabilities.hasDailyCheckIn) {
            Spacer(Modifier.height(8.dp))
            CheckInCard(onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } })
        }
        Spacer(Modifier.height(8.dp))
        if (loggedIn && JmCapabilities.hasCloudFavouriteRead) {
            MenuRow("☁ 云端收藏", onClick = onOpenCloudFavourites)
        }
        if (loggedIn && JmCapabilities.hasCloudHistory) {
            MenuRow("☁ 云端历史", onClick = onOpenCloudHistory)
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

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }
}

/**
 * 签到月历卡：活动名 + 当月日历（signed 打勾）+ 进度 + 签到按钮。
 * 数据来自 /daily?user_id=，执行走 POST /daily_chk {user_id, daily_id}（2026-09-25 实测）。
 */
@Composable
private fun CheckInCard(onMessage: (String) -> Unit) {
    var calendar by remember { mutableStateOf<JmDailyCalendar?>(null) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadCalendar() {
        loading = true
        calendar = runCatching { JmRepository.dailyCalendar() }.getOrNull()
        loading = false
    }

    LaunchedEffect(Unit) { loadCalendar() }

    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = calendar?.eventName?.takeIf { it.isNotBlank() } ?: "每日签到",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    calendar?.let { cal ->
                        Text(
                            text = "本月已签 ${cal.signedDays} 天 · 进度 ${cal.currentProgress}" +
                                if (cal.sevenDaysCoin.isNotBlank()) " · 连签7天奖 ${cal.sevenDaysCoin}币" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    calendar != null -> Button(
                        onClick = {
                            if (!checking) {
                                checking = true
                                scope.launch {
                                    try {
                                        onMessage(JmRepository.checkIn())
                                        loadCalendar()
                                    } catch (e: Exception) {
                                        onMessage(e.message ?: "签到失败")
                                    } finally {
                                        checking = false
                                    }
                                }
                            }
                        },
                        enabled = !checking,
                    ) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("签到")
                        }
                    }
                }
            }
            // 当月日历（record 外层是周行）：签到的日子填充圆点
            calendar?.let { cal ->
                if (cal.record.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cal.record.forEach { week ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                week.forEach { day ->
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (day.signed) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = day.date.removePrefix("0"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (day.signed) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
