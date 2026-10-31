package com.jmread.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.core.checkin.DailyCheckIn
import com.jmread.core.library.LibraryRepository
import com.jmread.data.SecureAccountStore
import com.jmread.data.SourcePrefs
import kotlinx.coroutines.launch

/**
 * 我的：未登录 = 纯本地中心（书架/下载/设置全部可用）；
 * 已登录 = 账号卡片 + 云端能力（收藏双写 / 签到自动完成）。
 * 统一书架上线后，云端收藏/云端历史/本地书架/阅读历史四入口收敛为一个「书架」
 * （docs/unified-library-design.md §5.1）。
 * 签到自 0.1.7 起后台自动化（DailyCheckIn），这里只展示结果、失败可点重试。
 */
@Composable
fun MineScreen(
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenAuthorFavourites: () -> Unit = {},
    onOpenFollowManage: () -> Unit = {},
    onOpenReader: (String, Int) -> Unit = { _, _ -> },
    onOpenLogin: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(JmRepository.isLoggedIn) }
    var account by remember { mutableStateOf(JmRepository.accountName) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var deleteSavedCredentials by remember { mutableStateOf(false) }

    // 统一书架条目流：入口副标题的「N 本收藏 · M 本在读」直接从并集取数
    val libraryItems by LibraryRepository.items.collectAsState()
    val collectedCount = libraryItems.count { it.collected }
    val readingCount = libraryItems.count { it.lastOrder != null && !it.finished }

    val snackbarHostState = remember { SnackbarHostState() }

    // 登录态从 false→true（本会话内刚完成登录）：flush 同步队列 + 一次性 backfill 询问。
    // 初次组合（持久会话恢复）不触发——backfill 只属于「刚登录」时刻。
    var lastKnownLoggedIn by remember { mutableStateOf(JmRepository.isLoggedIn) }
    var backfillPending by remember { mutableStateOf(0) }
    var showBackfillDialog by remember { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (loggedIn && JmCapabilities.hasDailyCheckIn) DailyCheckIn.maybeCheckIn()
        if (loggedIn && !lastKnownLoggedIn) {
            lastKnownLoggedIn = true
            LibraryRepository.flushPendingSync()
            val pending = LibraryRepository.backfillPendingCount()
            val declinedFor = SourcePrefs.current().backfillDeclinedUid
            if (pending > 0 && declinedFor != JmRepository.accountName && declinedFor != SourcePrefs.current().jmUid) {
                backfillPending = pending
                showBackfillDialog = true
            }
        } else if (loggedIn) {
            lastKnownLoggedIn = true
        }
    }

    if (showBackfillDialog) {
        AlertDialog(
            onDismissRequest = { showBackfillDialog = false },
            title = { Text("同步本地书架到云端") },
            text = {
                Text(
                    "检测到本地书架有 $backfillPending 本还没进云端收藏。" +
                        "同步后换设备、官方 App 里都能看到，要现在同步吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackfillDialog = false
                    scope.launch {
                        val (ok, fail) = LibraryRepository.backfillToCloud()
                        if (ok + fail > 0) {
                            snackbarHostState.showSnackbar(
                                if (fail == 0) "已把 $ok 本同步到云端收藏"
                                else "已同步 $ok 本，$fail 本失败（将自动重试）",
                            )
                        }
                    }
                }) { Text("同步到云端") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackfillDialog = false
                    scope.launch {
                        // 以账号标识记忆拒绝：同账号不再二次打扰，换账号重新询问
                        val uid = SourcePrefs.current().jmUid
                        SourcePrefs.current().setBackfillDeclinedUid(uid ?: (JmRepository.accountName ?: "unknown"))
                    }
                }) { Text("暂不") }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "JM Reader",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (loggedIn) "已登录 · ${account ?: "账号"}" else "未登录",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (loggedIn && JmCapabilities.hasDailyCheckIn) {
                        CheckInStatusText()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (loggedIn) {
                        "云端能力已解锁 · 收藏自动双写本地与云端"
                    } else {
                        "收藏与阅读记录保存在本机，登录后同步到云端"
                    },
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
        Spacer(Modifier.height(8.dp))
        MenuRow(
            label = "书架",
            subtitle = if (collectedCount + readingCount > 0) {
                "$collectedCount 本收藏 · $readingCount 本在读"
            } else {
                "收藏与阅读记录都在这里"
            },
            onClick = onOpenLibrary,
        )
        MenuRow("收藏的作者", onClick = onOpenAuthorFavourites)
        MenuRow("关注管理", onClick = onOpenFollowManage)
        MenuRow("下载", onClick = onOpenDownloads)
        MenuRow("设置", onClick = onOpenSettings)
        if (loggedIn) {
            MenuRow("退出登录") {
                showLogoutDialog = true
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )

    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = {
                Column {
                    Text("确定退出当前账号吗？退出后云端条目将从书架收起，本地数据保留。")
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
                        DailyCheckIn.reset()
                        loggedIn = false
                        account = null
                        lastKnownLoggedIn = false
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
}

/**
 * 用户信息行尾的自动签到状态：成功/已签只读展示，失败可点击重试。
 * 空态不占位（未登录 / 本会话还没尝试过）。
 */
@Composable
private fun CheckInStatusText() {
    val state by DailyCheckIn.state.collectAsState()
    when (val s = state) {
        DailyCheckIn.State.Idle -> {}
        DailyCheckIn.State.Running -> StatusText("自动签到中…", MaterialTheme.colorScheme.onSurfaceVariant)
        is DailyCheckIn.State.Done -> StatusText("已签 · ${s.msg}", MaterialTheme.colorScheme.primary)
        DailyCheckIn.State.Already -> StatusText("今日已签", MaterialTheme.colorScheme.onSurfaceVariant)
        is DailyCheckIn.State.Failed -> StatusText(
            "签到失败 · 点此重试",
            MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { DailyCheckIn.maybeCheckIn() },
        )
    }
}

@Composable
private fun StatusText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 12.dp),
    )
}

@Composable
private fun MenuRow(
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
    HorizontalDivider()
}
