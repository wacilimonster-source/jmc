# -*- coding: utf-8 -*-
"""SettingsScreen 单源改造：数据源组 → 网络组（域名自愈 + 手填）"""
import io

p = 'app/src/main/java/com/jmread/ui/settings/SettingsScreen.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''import com.jmread.core.source.SourceManager
import com.jmread.core.source.SourceType
import com.jmread.core.update.UpdateManager
import com.jmread.data.GridSettings
import com.jmread.data.ReaderPrefs
import kotlinx.coroutines.launch''',
'''import com.jmread.core.netconfig.DomainPool
import com.jmread.core.scramble.ScrambleCache
import com.jmread.core.update.UpdateManager
import com.jmread.data.GridSettings
import com.jmread.data.ReaderPrefs
import com.jmread.data.SourcePrefs
import kotlinx.coroutines.launch''')

s = s.replace('''fun SettingsScreen(
    /** 从"我的"页进入时为 push 页面，显示返回按钮 */
    onBack: (() -> Unit)? = null,
    onOpenLog: () -> Unit = {},
    onOpenSourceManage: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    // 登录态本身非响应式：401/登出会自增 tick，借此重查各源登录状态
    val unauthorizedTick by SourceManager.unauthorizedTick.collectAsState()
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader''',
'''fun SettingsScreen(
    /** 从"我的"页进入时为 push 页面，显示返回按钮 */
    onBack: (() -> Unit)? = null,
    onOpenLog: () -> Unit = {},
) {
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader''')

old_net = '''            // ── 数据源 ──────────────────────────────────────────────
            SettingsGroup(
                header = "数据源",
                supporting = "切换后首页 / 搜索 / 详情将展示该源的内容",
            ) {
                SourceType.entries.forEachIndexed { index, type ->
                    if (index > 0) SettingsRowDivider()
                    val loggedIn = remember(type, unauthorizedTick) {
                        SourceManager.sourceOf(type).isLoggedIn
                    }
                    SourceRow(
                        type = type,
                        selected = type == activeSource,
                        loggedIn = loggedIn,
                        onSelect = { scope.launch { SourceManager.switch(type) } },
                        onLogin = if (loggedIn) null else {
                            {
                                // 切源与导航必须串行：原实现先 launch 再同步导航，
                                // 登录页可能以旧源组合，把新源账号提交给旧源
                                scope.launch {
                                    if (type != activeSource) SourceManager.switch(type)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                        onOpenLogin()
                                    }
                                }
                            }
                        },
                    )
                }
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("数据源管理") },
                    supportingContent = { Text("账号登录 · API 域名等高级设置") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeSource.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            ChevronIcon()
                        }
                    },
                    modifier = Modifier.clickable(onClick = onOpenSourceManage),
                )
            }'''

new_net = '''            // ── 网络 ────────────────────────────────────────────────
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
            }'''

assert old_net in s, "net block not found"
s = s.replace(old_net, new_net)

# 删除 SourceRow 函数
old_row = '''/** 数据源单选行；未登录时行尾附"去登录"，点击会切到该源并进入登录页 */
@Composable
private fun SourceRow(
    type: SourceType,
    selected: Boolean,
    loggedIn: Boolean,
    onSelect: () -> Unit,
    onLogin: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(type.displayName) },
        supportingContent = { Text(if (loggedIn) "已登录" else "未登录") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onLogin != null) {
                    TextButton(onClick = onLogin) { Text("去登录") }
                }
                RadioButton(selected = selected, onClick = onSelect)
            }
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

'''
assert old_row in s, "SourceRow not found"
s = s.replace(old_row, '')

s = s.replace('headlineContent = { Text("关于 PiKA") },', 'headlineContent = { Text("关于 JM Reader") },')
s = s.replace('''    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 PiKA") },
        text = {
            Column {
                Text(
                    "PiKA · 聚合漫画阅读器",
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
                    "聚合哔咔漫画与禁漫天堂双数据源，支持搜索、分类浏览、关注流、下载与本地阅读进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )''',
'''    AlertDialog(
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
                )''')

io.open(p, 'w', encoding='utf-8').write(s)
print("done")
