package com.jmread.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 统一书架上线后，云端收藏/云端历史/本地书架/阅读历史四入口收敛为一个「书架」。
 * 签到自 0.1.7 起后台自动化（DailyCheckIn），这里只展示结果、失败可点重试。
 * 游客卡片说明本机保存即可，登录为可选操作（不给游客伪造等级）。
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

    // 刚完成显式登录（由 JmRepository 记录的一次性事件）：flush 同步队列 + 一次性 backfill 询问。
    // 不能用「组合内 false→true」判断：登录页返回时 Mine 是重新组合的，初值已经是 true。
    var backfillPending by remember { mutableStateOf(0) }
    var showBackfillDialog by remember { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (loggedIn && JmCapabilities.hasDailyCheckIn) DailyCheckIn.maybeCheckIn()
        if (loggedIn && JmRepository.consumePostLoginEvent()) {
            LibraryRepository.flushPendingSync()
            LibraryRepository.refreshCloud(force = true)
            val pending = LibraryRepository.backfillPendingCount()
            val declinedFor = SourcePrefs.current().backfillDeclinedUid
            if (pending > 0 && declinedFor != JmRepository.accountName && declinedFor != SourcePrefs.current().jmUid) {
                backfillPending = pending
                showBackfillDialog = true
            }
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "我的",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
            )
            // 账号卡片：游客态说明本机保存；登录后展示真实账号与自动签到状态
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (loggedIn) "已登录 · ${account ?: "账号"}" else "本机使用中",
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
                            "收藏和阅读记录保存在本机。登录可使用云端收藏与历史。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!loggedIn) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenLogin) { Text("登录账号（可选）") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 阅读资产组：书架为主入口
            MenuGroup {
                MenuRow(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    label = "书架",
                    subtitle = if (collectedCount + readingCount > 0) {
                        "$collectedCount 本收藏 · $readingCount 本在读"
                    } else {
                        "收藏与阅读记录都在这里"
                    },
                    onClick = onOpenLibrary,
                )
            }
            Spacer(Modifier.height(12.dp))
            MenuGroup {
                MenuRow(
                    icon = Icons.Filled.PersonSearch,
                    label = "收藏的作者",
                    onClick = onOpenAuthorFavourites,
                )
                MenuRowDivider()
                MenuRow(
                    icon = Icons.Filled.AlternateEmail,
                    label = "关注管理",
                    onClick = onOpenFollowManage,
                )
            }
            Spacer(Modifier.height(12.dp))
            MenuGroup {
                MenuRow(
                    icon = Icons.Filled.Download,
                    label = "下载",
                    subtitle = "已下载内容离线可读",
                    onClick = onOpenDownloads,
                )
                MenuRowDivider()
                MenuRow(
                    icon = Icons.Filled.Settings,
                    label = "设置",
                    onClick = onOpenSettings,
                )
            }
            if (loggedIn) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "退出登录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogoutDialog = true }
                        .padding(vertical = 14.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "JM Reader · 与 PiKA 同系列",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
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
                            val result = LibraryRepository.backfillToCloud()
                            if (result == null) {
                                snackbarHostState.showSnackbar("云端收藏状态拉取失败，请检查网络后重试")
                            } else {
                                val (ok, fail) = result
                                if (ok + fail > 0) {
                                    snackbarHostState.showSnackbar(
                                        if (fail == 0) "已把 $ok 本同步到云端收藏"
                                        else "已同步 $ok 本，$fail 本失败（将自动重试）",
                                    )
                                }
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
    }
}

/** 分组卡片容器：14dp 圆角 + surfaceContainer（系列分组规范） */
@Composable
private fun MenuGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun MenuRowDivider() {
    HorizontalDivider(Modifier.padding(start = 52.dp))
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(14.dp))
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
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
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
