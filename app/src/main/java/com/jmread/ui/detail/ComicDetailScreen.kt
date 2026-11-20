package com.jmread.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.jmread.core.model.ComicChapter
import com.jmread.core.model.ComicComment
import com.jmread.core.model.ComicDetail
import com.jmread.core.model.ComicSummary
import com.jmread.data.ChaptersReadDoc
import com.jmread.data.DownloadPrefs
import com.jmread.data.ReaderPrefs

/** 漫画详情：封面 + 信息 + 简介 + 章节列表 + 相关推荐 + 评论区（评论通过 FAB 弹窗） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComicDetailScreen(
    ref: String,
    onBack: () -> Unit,
    onOpenReader: (String, Int) -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    onOpenAuthorWithOrigin: (String, String) -> Unit = { seg, _ -> onOpenAuthor(seg) },
    onOpenTagSearch: (String) -> Unit = {},
    onComicClick: (String) -> Unit = {},
    viewModel: ComicDetailViewModel = viewModel(),
) {
    val comic by viewModel.comic.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadError by viewModel.error.collectAsState()
    val chaptersError by viewModel.chaptersError.collectAsState()
    val favourited by viewModel.favourited.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val commentLoading by viewModel.commentLoading.collectAsState()
    val commentEndReached by viewModel.commentEndReached.collectAsState()
    val commentError by viewModel.commentError.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val subComments by viewModel.subComments.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val lastProgress by viewModel.lastProgress.collectAsState()
    val favouriteError by viewModel.favouriteError.collectAsState()
    var descExpanded by remember { mutableStateOf(false) }
    var showCommentDialog by remember { mutableStateOf(false) }
    val downloadTasks by com.jmread.core.download.DownloadManager.tasks.collectAsState()
    val downloadedOrders by viewModel.downloadedOrders.collectAsState()
    val readChapterKeys by viewModel.readChapterKeys.collectAsState()
    val chapterDesc by ReaderPrefs.current().chapterListDescending.collectAsState(initial = false)
    val displayChapters = remember(chapters, chapterDesc) {
        if (chapterDesc) chapters.asReversed() else chapters
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 下载完成通知在 Android 13+ 需要运行时权限。此前只在设置页手动开关时申请，
    // 而开关默认是开着的，新装用户永远不会收到通知；这里在真正发起下载时补一次申请。
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // 与设置页行为一致：拒绝后关闭开关，避免每次下载都弹系统框
            scope.launch { DownloadPrefs.setNotifyEnabled(false) }
        }
        val action = pendingDownload
        pendingDownload = null
        action?.invoke()
    }
    fun runWithNotificationPermission(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            DownloadPrefs.notifyEnabled &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingDownload = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    // 单源 App：路由参数即漫画 id
    val comicId = ref

    // P3-4：横屏首屏高度有限（约 350dp），主操作按钮原独立 item 会被压到折叠线以下。
    // 横屏时把操作区并入封面右侧信息列（左封面右信息+按钮）；竖屏保持原有独立操作区。
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val narrowLayout = configuration.screenWidthDp < 360 ||
        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val onRead: () -> Unit = {
        val target = lastProgress?.order ?: chapters.firstOrNull()?.order
        if (target != null) onOpenReader(ref, target)
    }
    val onDownloadAll: () -> Unit = {
        runWithNotificationPermission { viewModel.downloadAll(comicId, comic, chapters) }
    }
    val detailActions: @Composable () -> Unit = {
        DetailActions(
            hasProgress = lastProgress != null,
            progressLabel = lastProgress?.let { "上次阅读到 第${it.order}话 · 第${it.pageIndex + 1}页" },
            chaptersEnabled = chapters.isNotEmpty(),
            narrowLayout = narrowLayout,
            chaptersError = chaptersError,
            maxWidth = if (landscape) 420.dp else null,
            onRead = onRead,
            onDownloadAll = onDownloadAll,
            onRetryChapters = { viewModel.retryChapters(comicId) },
        )
    }

    LaunchedEffect(ref) {
        viewModel.load(ref)
        // 从阅读器返回时页面会被重新组合：顺手刷新「继续阅读」进度
        viewModel.refreshProgress()
    }
    // 复合署名选择层（对标 PiKA v1.10.0）：解析出多片段时点击作者先弹层
    var authorPickerOrigin by remember { mutableStateOf<String?>(null) }
    authorPickerOrigin?.let { origin ->
        com.jmread.ui.author.AuthorPickerSheet(
            origin = origin,
            onDismiss = { authorPickerOrigin = null },
            onPick = { seg ->
                authorPickerOrigin = null
                onOpenAuthorWithOrigin(seg, origin)
            },
        )
    }
    // ON_RESUME 兜底刷新（对标 PiKA v1.12.3-3）：无论组合是否重建，回到前台/返回详情
    // 都立即刷新本地进度，「继续阅读」按钮与上次读到的话/页保持实时
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(favouriteError) {
        favouriteError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeFavouriteError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(comic?.title ?: "详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (viewModel.canFavourite()) {
                        IconButton(onClick = { viewModel.favourite() }) {
                            Icon(
                                imageVector = if (favourited) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = "收藏",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // 对标 PiKA v1.12.0：顶栏可见复制入口（复制作品名/作者名）
                    comic?.let { c ->
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("复制作品名") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            com.jmread.ui.common.copyToClipboard(context, "作品名", c.title),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制作者名") },
                                onClick = {
                                    menuOpen = false
                                    val joined = c.authors.joinToString(" / ")
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            com.jmread.ui.common.copyToClipboard(context, "作者名", joined),
                                        )
                                    }
                                },
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val c = comic
            if (c != null) {
                item {
                    // P3-4：横屏时主操作按钮直接挂在封面右侧信息列末尾，首屏完整可见
                    ComicHeader(
                        comic = c,
                        favourited = favourited,
                        onOpenAuthor = onOpenAuthor,
                        onCopyTitle = { scope.launch { snackbarHostState.showSnackbar(com.jmread.ui.common.copyToClipboard(context, "作品名", c.title)) } },
                        onCopyAuthor = { a -> scope.launch { snackbarHostState.showSnackbar(com.jmread.ui.common.copyToClipboard(context, "作者名", a)) } },
                        onAuthorClick = { a ->
                            if (com.jmread.core.model.AuthorName.parse(a).hasMultiple) {
                                authorPickerOrigin = a
                            } else {
                                onOpenAuthor(a)
                            }
                        },
                        actions = if (landscape) detailActions else null,
                    )
                }
                if (c.isPaid) {
                    // 付费本（price 非空）：透明提示，不静默给空白页
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Text(
                                text = "该作品为付费内容，本应用仅提供试看；完整内容请在禁漫 App 内获取。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                if (!landscape) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            detailActions()
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Text(
                        text = if (c.description.isBlank()) "暂无简介" else c.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (c.description.length > 40) {
                        Text(
                            text = if (descExpanded) "收起" else "展开",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { descExpanded = !descExpanded },
                        )
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "标签",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(c.tags.ifEmpty { c.categories }) { tag ->
                            SuggestionChip(
                                onClick = { onOpenTagSearch(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                if (c.works.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "作品",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(c.works) { w ->
                                SuggestionChip(
                                    onClick = { onOpenTagSearch(w) },
                                    label = { Text(w) },
                                )
                            }
                        }
                    }
                }
                if (c.actors.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "登场角色",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(c.actors) { a ->
                                SuggestionChip(
                                    onClick = { onOpenTagSearch(a) },
                                    label = { Text(a) },
                                )
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "章节（${c.epsCount}）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (chapters.size > 1) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        ReaderPrefs.current().setChapterListDescending(!chapterDesc)
                                    }
                                },
                            ) {
                                Text(if (chapterDesc) "倒序 ↓" else "正序 ↑")
                            }
                        }
                        val done = downloadTasks.count {
                            it.task.comicId == comicId && it.isFinished
                        }
                        if (done > 0) {
                            Text(
                                text = "已下载 $done/${chapters.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                items(displayChapters, key = { it.id.ifBlank { "order:${it.order}" } }) { chapter ->
                    // 查 VM 在 IO 线程预算好的集合：此前在组合期同步调 isDownloaded
                    // （内部 listFiles 目录遍历），列表每项都会做一次磁盘 IO
                    val read = if (chapter.id.isNotBlank()) {
                        chapter.id in readChapterKeys
                    } else {
                        ChaptersReadDoc.orderKey(chapter.order) in readChapterKeys
                    }
                    ChapterRow(
                        chapter = chapter,
                        onClick = { onOpenReader(ref, chapter.order) },
                        onDownload = {
                            runWithNotificationPermission {
                                viewModel.downloadChapter(comicId, comic, chapter)
                            }
                        },
                        downloaded = chapter.order in downloadedOrders,
                        read = read,
                    )
                }
                if (loading && chapters.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "加载章节中...",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                if (recommendations.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "猜你喜欢",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(recommendations, key = { it.id }) { rec ->
                                RecommendCard(rec, onClick = { onComicClick(rec.ref) })
                            }
                        }
                    }
                }
                if (viewModel.commentSupported) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                        Text(
                            text = "评论（${c.commentsCount}）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        // 发评论能力未验证（JmCapabilities.hasCommentWrite=false）：入口不渲染
                    }
                    }
                    commentError?.let { err ->
                        item {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (comments.isEmpty() && !commentLoading && commentError != null) {
                        item {
                            Text(
                                text = "评论加载失败 · 点击重试",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .clickable { viewModel.loadComments(comicId, 1) },
                            )
                        }
                    } else if (comments.isEmpty() && !commentLoading) {
                        item {
                            Text(
                                text = "暂无评论",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(comments, key = { it.id }) { comment ->
                        Box(
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        ) {
                            CommentItem(
                                comment = comment,
                                subComments = subComments[comment.id].orEmpty(),
                                replying = replyingTo == comment.id,
                                onReply = { viewModel.setReplyingTo(comment.id) },
                                onToggleSub = { viewModel.toggleSubComments(comment.id) },
                            )
                        }
                    }
                    if (commentLoading) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.padding(12.dp))
                            }
                        }
                    }
                    if (!commentEndReached && comments.isNotEmpty() && !commentLoading) {
                        item {
                            Text(
                                text = "加载更多评论",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .clickable {
                                        viewModel.loadComments(comicId, viewModel.commentPage + 1)
                                    },
                            )
                        }
                    }
                }
            } else {
                item {
                    Box(Modifier.fillMaxSize().padding(top = 160.dp), contentAlignment = Alignment.TopCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (loadError != null) {
                                // 此前详情失败永远显示"加载中"，无提示无重试，只能退出重进
                                Text(
                                    text = "加载失败：$loadError",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "点击重试",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .clickable { viewModel.load(ref) },
                                )
                                // 通知直达详情时若离线，直接给已下载章节入口（P0-04）
                                val localOrders = downloadTasks
                                    .filter { it.task.comicId == comicId && it.isFinished }
                                    .map { it.task.order }
                                    .sorted()
                                if (localOrders.isNotEmpty()) {
                                    Button(
                                        onClick = { onOpenReader(ref, localOrders.first()) },
                                        modifier = Modifier.padding(top = 12.dp),
                                    ) { Text("打开本地下载") }
                                }
                            } else {
                                Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    var commentSendError by remember { mutableStateOf<String?>(null) }
    if (showCommentDialog && viewModel.commentSupported) {
        CommentDialog(
            replyingTo = replyingTo,
            sending = sending,
            sendError = commentSendError,
            onSend = { content ->
                viewModel.send(content, onSent = { err ->
                    if (err == null) {
                        showCommentDialog = false
                        commentSendError = null
                    } else {
                        // 发送失败必须保留对话框与已输入内容：静默关闭会让长评无法找回
                        commentSendError = err
                    }
                })
            },
            onCancelReply = { viewModel.setReplyingTo(null) },
            onDismiss = {
                showCommentDialog = false
                commentSendError = null
                viewModel.setReplyingTo(null)
            },
        )
    }
}

@Composable
private fun CommentDialog(
    replyingTo: String?,
    sending: Boolean,
    onSend: (String) -> Unit,
    onCancelReply: () -> Unit,
    onDismiss: () -> Unit,
    sendError: String? = null,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replyingTo != null) "回复评论" else "写评论") },
        text = {
            Column {
                if (replyingTo != null) {
                    Text(
                        text = "点击此处取消回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onCancelReply)
                            .padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("说点什么...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sendError != null) {
                    Text(
                        text = "发送失败：$sendError（内容已保留，可重发）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(input.trim()) },
                enabled = !sending && input.isNotBlank(),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("发送")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CommentItem(
    comment: ComicComment,
    subComments: List<ComicComment>,
    replying: Boolean,
    onReply: () -> Unit,
    onToggleSub: () -> Unit,
) {
    // 治理形态：剧透默认折叠、导流广告灰显折叠（内容本身不过滤）
    var revealed by remember(comment.id) { mutableStateOf(false) }
    val collapsed = (comment.isSpoiler || comment.isAd) && !revealed
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Box(Modifier.size(32.dp).clip(CircleShape)) {
                comment.user?.avatarUrl?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.user?.name ?: "匿名",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    comment.user?.levelName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(
                        text = comment.createdAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (collapsed) {
                    Text(
                        text = if (comment.isAd) "疑似导流广告，点击展开" else "包含剧透内容，点击展开",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (comment.isAd) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { revealed = true },
                    )
                } else {
                    Text(
                        text = comment.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (comment.isAd) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = "赞 ${comment.likesCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (comment.replies.isNotEmpty() || comment.commentsCount > 0) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (subComments.isEmpty()) "展开回复" else "收起回复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onToggleSub),
                        )
                    }
                }
            }
        }
        if (subComments.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 40.dp, top = 4.dp),
            ) {
                subComments.forEach { sub ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            text = "${sub.user?.name ?: "匿名"}：${if (sub.isAd) "[疑似广告]" else sub.content}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sub.isAd) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun RecommendCard(comic: ComicSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(96.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                comic.coverUrl?.let {
                    com.jmread.ui.common.RetryableCoverImage(
                        url = it,
                        contentDescription = comic.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = comic.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 详情主操作区（P3-4 从竖屏专用 item 抽出，横竖屏共用）：
 * 继续阅读/下载整本 + 上次进度 + 章节失败重试。
 * [narrowLayout]：窄屏/大字体时主按钮整行、次按钮另排，避免横向拥挤（I12）；
 * [maxWidth]：操作区宽度上限，横屏挂在封面右侧时防主按钮被拉伸过宽。
 */
@Composable
private fun DetailActions(
    hasProgress: Boolean,
    progressLabel: String?,
    chaptersEnabled: Boolean,
    narrowLayout: Boolean,
    chaptersError: String?,
    maxWidth: Dp?,
    onRead: () -> Unit,
    onDownloadAll: () -> Unit,
    onRetryChapters: () -> Unit,
) {
    Column(
        modifier = if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier,
    ) {
        if (narrowLayout) {
            Button(
                onClick = onRead,
                enabled = chaptersEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            { Text(if (hasProgress) "继续阅读" else "开始阅读") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDownloadAll,
                enabled = chaptersEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("下载整本")
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRead,
                    enabled = chaptersEnabled,
                    modifier = Modifier.weight(1f),
                )
                { Text(if (hasProgress) "继续阅读" else "开始阅读") }
                OutlinedButton(
                    onClick = onDownloadAll,
                    enabled = chaptersEnabled,
                )
                {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("下载整本")
                }
            }
        }
        if (hasProgress && progressLabel != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 章节加载失败必须可见：否则章节恒空时"开始阅读/下载整本"
        // 永久禁用且没有任何失败线索（此前 catch 空处理吞掉了错误）
        if (chaptersError != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "章节加载失败：$chaptersError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(onClick = onRetryChapters),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicHeader(
    comic: ComicDetail,
    favourited: Boolean,
    onOpenAuthor: (String) -> Unit,
    onCopyTitle: () -> Unit = {},
    onCopyAuthor: (String) -> Unit = {},
    /** 作者点按下沉：复合署名弹选择层、单一署名直达（选择层状态在调用方） */
    onAuthorClick: (String) -> Unit = onOpenAuthor,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(110.dp).aspectRatio(3f / 4f)) {
            comic.coverUrl?.let {
                com.jmread.ui.common.RetryableCoverImage(
                    url = it,
                    contentDescription = comic.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 对标 PiKA v1.12.0：长按标题一步复制作品名
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onCopyTitle),
            )
            Spacer(Modifier.height(4.dp))
            // 多作者逐个可点（作者维度搜索 main_tag=2）；作者数据是数组
            if (comic.authors.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "作者：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    comic.authors.forEachIndexed { i, a ->
                        Text(
                            text = (if (i > 0) " / " else "") + a,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            // 点按进作者页（单一署名直达；复合署名先弹选择层）；长按复制该作者名
                            modifier = Modifier.combinedClickable(
                                onClick = { onAuthorClick(a) },
                                onLongClick = { onCopyAuthor(a) },
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                // 本源接口没有连载/完结字段：不渲染未经证实的「连载中/已完结」
                text = "${comic.pagesCount}P · ${comic.epsCount} 话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "阅读 ${comic.totalViews} · 赞 ${comic.totalLikes} · 评 ${comic.commentsCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (comic.updatedAt.isNotBlank() || comic.createdAt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        if (comic.updatedAt.isNotBlank()) append("更新 ${comic.updatedAt.take(10)}")
                        if (comic.updatedAt.isNotBlank() && comic.createdAt.isNotBlank()) append(" · ")
                        if (comic.createdAt.isNotBlank()) append("上传 ${comic.createdAt.take(10)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 用实时收藏态而不是 /album.is_favorite（后者在取消收藏后仍可能是旧值）
            if (favourited) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (com.jmread.core.JmRepository.isLoggedIn) "已收藏 · 本地与云端" else "已收藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = comic.categories.joinToString(" / "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // P3-4：横屏时主操作区挂在信息列末尾（竖屏传 null 不渲染）
            if (actions != null) {
                Spacer(Modifier.height(10.dp))
                actions()
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ComicChapter,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    downloaded: Boolean,
    read: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.title.ifBlank { "第 ${chapter.order} 话" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (read) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已读",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 4.dp),
            )
        }
        if (downloaded) {
            Text(
                text = "已下载",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        IconButton(onClick = onDownload) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = if (downloaded) "重新下载" else "下载",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
