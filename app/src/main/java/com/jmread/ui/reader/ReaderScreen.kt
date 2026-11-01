package com.jmread.ui.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.jmread.core.MemoryBudget
import com.jmread.core.scramble.ImageScrambler
import com.jmread.core.scramble.jmImageCacheKey
import com.jmread.data.ChaptersReadDoc
import com.jmread.data.ReaderPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 阅读器（重构版）：
 *
 * - **滚动流（条漫，默认）**：LazyColumn 纵向滚动；超长图自动切分为多屏，贴合竖屏。
 * - **横滑翻页**：HorizontalPager + 双击缩放；单页加载/失败重试/前后预取。
 * - **手势**：点按左/右边缘翻页（滚动流为翻屏）、中间点按唤出控制面板、双击放大、
 *   长按保存/分享（P1-04）。
 * - **进度**：作品最近进度 + 每章进度（稳定 chapterId）；重进自动续读。
 * - **章节抽屉**：跳章、已读勾、已下载标记、正/倒序切换（P0-01/P1-02/P1-05）。
 * - **音量键翻页**、首次引导、章节末页已读判定。
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReaderScreen(
    ref: String,
    order: Int,
    onBack: () -> Unit,
    title: String = "",
    viewModel: ReaderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages by viewModel.pages.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val chaptersResolved by viewModel.chaptersResolved.collectAsState()
    val downloadedOrders by viewModel.downloadedOrders.collectAsState()
    val readChapterKeys by viewModel.readChapterKeys.collectAsState()
    val epTitle by viewModel.epTitle.collectAsState()
    val comicTitle by viewModel.comicTitle.collectAsState()
    val loading by viewModel.loading.collectAsState()
    // 当前章节号以 VM 为准：切章只改 VM，导航路由参数不变，
    // 上下话按钮/标题按路由算会错乱（第二次"下一话"失效、上一话跳错章）
    val vmOrder by viewModel.currentOrderFlow.collectAsState()

    val volumeKeyPaging by ReaderPrefs.current().volumeKeyPaging.collectAsState(initial = false)
    val chapterDesc by ReaderPrefs.current().chapterListDescending.collectAsState(initial = false)
    // 初值 true：偏好读盘完成前不闪引导层；读到 false 才展示
    val guideShown by ReaderPrefs.current().readerGuideShown.collectAsState(initial = true)
    var guideDismissed by remember { mutableStateOf(false) }

    var scrollMode by remember { mutableStateOf(ReaderPrefs.current().readerMode == 0) }
    var showPanel by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var showPageActionSheet by remember { mutableStateOf(false) }
    var pageActionTarget by remember { mutableIntStateOf(-1) }

    // 手动翻转兜底：url -> 1=强制倒序 / -1=强制原样 / 缺席=自动判定。
    // 自动判定逐图进行，极端情况下会漏判/误翻，这里给用户一个手动开关
    val manualFlip = remember { mutableStateMapOf<String, Int>() }
    fun displayUrl(p: Int): String {
        val u = pages.getOrNull(p)?.imageUrl ?: return ""
        return when (manualFlip[u]) {
            1 -> "${u}#m1"
            -1 -> "${u}#m0"
            else -> u
        }
    }

    // 本页实际渲染成功/失败：已读判定只认“末页可见且加载成功”（P1-05）
    val loadedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val loadedRows = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(vmOrder) {
        loadedPages.clear()
        loadedRows.clear()
    }

    var brightness by remember { mutableFloatStateOf(ReaderPrefs.current().brightness) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    // 缩放平移偏移：双击放大后允许拖动查看画面边缘（否则放大后只能看正中央）
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    val configuration = LocalConfiguration.current
    val viewportAspect = remember(configuration) {
        if (configuration.screenWidthDp <= 0) 1.8f
        else (configuration.screenHeightDp.toFloat() / configuration.screenWidthDp.toFloat())
            .coerceAtLeast(0.1f)
    }

    val listState = rememberLazyListState()
    // 末页之后多一页结束卡；页码/进度始终按真实页计算，结束卡不参与
    val pagerState = rememberPagerState(pageCount = { if (pages.isEmpty()) 0 else pages.size + 1 })

    // ── 沉浸式全屏管理 ─────────────────────────────────────────────────────
    val view = LocalView.current
    val windowInsetsController = remember {
        val activity = (context as? android.app.Activity) ?: return@remember null
        WindowCompat.getInsetsController(activity.window, view)
    }

    // 进入时隐藏系统栏，退出时恢复
    DisposableEffect(Unit) {
        windowInsetsController?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // showPanel 与系统栏同步：显示面板时显示系统栏，隐藏面板时隐藏系统栏
    LaunchedEffect(showPanel, showChapterSheet, showPageActionSheet) {
        if (showPanel || showChapterSheet || showPageActionSheet) {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 屏幕常亮
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 音量键需要焦点：进入阅读器即请求一次
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // ── 加载章节
    LaunchedEffect(ref, order) {
        viewModel.load(context, ref, order)
    }

    // ── 滚动流：页 → 行 平铺（长图分割） ──────────────────────────────────
    val sliceCounts = remember { mutableStateMapOf<Int, Int>() }
    // 每页实测高宽比：同页所有切片共用同一个比例，否则先加载的切片用真值、
    // 后组合的切片用估算值，相邻切片按不同比例摆放就会内容错位重叠。
    val pageRatios = remember { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(scrollMode) {
        if (!scrollMode) {
            sliceCounts.clear()
            pageRatios.clear()
        }
    }
    // 切章必须以 VM 的当前章节为准清空并预置切片数缓存：
    // 路由 order 在切章后不变，按它做 key 会让上一章的切片数污染新章的行换算；
    // 预置历史缓存后恢复定位前就有真实"页→行"换算，长图章节不再系统性偏早
    LaunchedEffect(vmOrder, pages.size) {
        sliceCounts.clear()
        pageRatios.clear()
        if (pages.isNotEmpty()) {
            val cached = com.jmread.data.WebtoonSliceCache.load(ref, vmOrder, context)
            for ((page, info) in cached) {
                sliceCounts[page] = info.count
                info.ratio?.let { pageRatios[page] = it }
            }
        }
    }
    val rows: List<Pair<Int, Int>> = if (scrollMode) {
        buildList {
            for (p in pages.indices) {
                val n = (sliceCounts[p] ?: 1).coerceAtLeast(1)
                repeat(n) { s -> add(p to s) }
            }
        }
    } else {
        pages.indices.map { it to 0 }
    }

    // 切片前缀和：sliceCounts 变化时重建一次（O(n)），行→页查询降为 O(log n)
    val rowPrefix by remember(pages.size, scrollMode) {
        derivedStateOf {
            val arr = IntArray(pages.size + 1)
            for (p in pages.indices) {
                arr[p + 1] = arr[p] + (sliceCounts[p] ?: 1).coerceAtLeast(1)
            }
            arr
        }
    }

    fun rowToPage(row: Int): Int {
        if (pages.isEmpty()) return 0
        val prefix = rowPrefix
        val r = row.coerceAtLeast(0)
        val size = pages.size
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (prefix[mid + 1] > r) hi = mid else lo = mid + 1
        }
        return lo
    }

    fun rowForPage(page: Int): Int =
        rowPrefix[page.coerceIn(0, pages.size)]

    // 进度恢复：先按当前已知切片粗定位；随后等切片解析完成再校正（上限 3 秒）。
    val pendingRestore by viewModel.pendingRestorePage.collectAsState()
    val restoring = remember { mutableStateOf(false) }
    LaunchedEffect(pages.size, pendingRestore, scrollMode) {
        if (pages.isNotEmpty() && pendingRestore >= 0) {
            val target = pendingRestore.coerceAtMost(pages.size - 1)
            restoring.value = true
            try {
                if (!scrollMode) {
                    pagerState.scrollToPage(target)
                    return@LaunchedEffect
                }
                listState.scrollToItem(rowForPage(target))
                val deadline = System.currentTimeMillis() + 3_000
                var lastRow = -1
                while (System.currentTimeMillis() < deadline) {
                    delay(200)
                    val row = rowForPage(target)
                    if (row != lastRow) {
                        val nearLast = lastRow == -1 ||
                            kotlin.math.abs(listState.firstVisibleItemIndex - lastRow) <= 2
                        if (nearLast) listState.scrollToItem(row)
                        lastRow = row
                    }
                    if ((0 until target).all { sliceCounts.containsKey(it) }) break
                }
            } finally {
                restoring.value = false
                viewModel.consumePendingRestore()
            }
        }
    }

    // 滚动流当前页：监听 firstVisibleItemIndex（snapshotFlow）
    val scrollVisiblePage = remember { mutableIntStateOf(0) }
    LaunchedEffect(listState, pages.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { row -> scrollVisiblePage.value = rowToPage(row) }
    }
    val currentPage = if (scrollMode) {
        scrollVisiblePage.value.coerceAtMost((pages.size - 1).coerceAtLeast(0))
    } else {
        pagerState.currentPage.coerceAtMost((pages.size - 1).coerceAtLeast(0))
    }

    // 保存进度：滚动页码变化做 1 秒防抖；切后台/退出立即保存兜底。
    LaunchedEffect(vmOrder, pages.size, scrollMode) {
        snapshotFlow {
            if (scrollMode) {
                scrollVisiblePage.value
            } else {
                pagerState.currentPage.coerceAtMost((pages.size - 1).coerceAtLeast(0))
            }
        }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { page ->
                if (pages.isNotEmpty() && !restoring.value) {
                    viewModel.saveProgress(page)
                    viewModel.recordRecentRead(page)
                }
            }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!restoring.value) {
            viewModel.saveProgress(currentPage)
            viewModel.recordRecentRead(currentPage)
        }
    }
    // 退出阅读器兜底：final=true 落到不随 VM 取消的作用域
    val latestPage by rememberUpdatedState(currentPage)
    DisposableEffect(Unit) {
        onDispose {
            if (pages.isNotEmpty() && !restoring.value) {
                viewModel.saveProgress(latestPage, final = true)
                viewModel.recordRecentRead(latestPage, final = true)
            }
        }
    }

    // 预加载前后 2 页；低内存/系统压力时 VM 内部直接跳过
    LaunchedEffect(currentPage, scrollMode) {
        if (pages.isNotEmpty()) viewModel.preloadNearby(context, currentPage, scrollMode, range = 2)
    }

    // ── 章节 ──────────────────────────────────────────────────────────────
    val sortedChapters = remember(chapters) { chapters.sortedBy { it.order } }
    val currentChapterIndex = sortedChapters.indexOfFirst { it.order == vmOrder }
    val currentChapterTitle = sortedChapters.firstOrNull { it.order == vmOrder }?.title ?: epTitle
    // 仅“完整目录成功加载且只有一章”才隐藏入口；加载中/失败/离线兜底都保留
    val showChapterButton = !(chaptersResolved && sortedChapters.size <= 1)
    fun switchTo(targetOrder: Int) {
        showPanel = false
        showChapterSheet = false
        viewModel.switchChapter(context, targetOrder)
    }

    /** 页码滑杆：松手后才跳页并保存一次进度，拖动过程不写盘 */
    fun seekToPage(page: Int) {
        if (pages.isEmpty()) return
        val target = page.coerceIn(0, pages.size - 1)
        scope.launch {
            if (scrollMode) {
                listState.scrollToItem(rowForPage(target))
            } else {
                pagerState.scrollToPage(target)
            }
            viewModel.saveProgress(target)
            viewModel.recordRecentRead(target)
        }
    }

    /** 音量键/点按共用的翻页：翻页模式逐页，滚动流约 0.9 屏 */
    fun pageTurn(zone: Int) {
        if (pages.isEmpty()) return
        if (scrollMode) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            if (viewportHeight > 0) {
                scope.launch { listState.animateScrollBy(viewportHeight * 0.9f * zone) }
            }
        } else {
            val target = (pagerState.currentPage + zone)
                .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    // 点按：左 30% 上翻 / 右 30% 下翻 / 中间 40% 面板
    fun onTap(offsetX: Float, width: Int) {
        val zone = when {
            offsetX < width * 0.3f -> -1
            offsetX > width * 0.7f -> 1
            else -> 0
        }
        when {
            zone == 0 -> showPanel = !showPanel
            else -> pageTurn(zone)
        }
    }

    /** 长按位置 → 页：滚动流按可见行换算，翻页模式取当前页 */
    fun pageAtOffset(y: Float): Int {
        if (pages.isEmpty()) return 0
        if (!scrollMode) return pagerState.currentPage.coerceIn(0, pages.size - 1)
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            y >= it.offset && y <= it.offset + it.size
        } ?: listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == listState.firstVisibleItemIndex
        }
        return rowToPage(info?.index ?: 0)
    }

    // 保存/分享（P1-04）：先冻结页码，权限通过后再执行
    var pendingSavePage by remember { mutableStateOf<Int?>(null) }
    fun performSave(page: Int) {
        val url = displayUrl(page)
        if (url.isBlank()) return
        val comic = comicTitle.ifBlank { "JM" }
        val chapter = currentChapterTitle.ifBlank { "第 $vmOrder 话" }
        scope.launch {
            val result = runCatching {
                ImageSaver.saveToGallery(context, url, comic, chapter, page + 1)
            }
            Toast.makeText(
                context,
                if (result.isSuccess) {
                    "已保存到相册"
                } else {
                    "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val savePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val page = pendingSavePage
        pendingSavePage = null
        if (granted && page != null) {
            performSave(page)
        } else {
            Toast.makeText(context, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveWithPermission(page: Int) {
        val needsLegacyPermission = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingSavePage = page
            savePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performSave(page)
        }
    }

    fun sharePage(page: Int) {
        val url = displayUrl(page)
        if (url.isBlank()) return
        val comic = comicTitle.ifBlank { "JM" }
        val chapter = currentChapterTitle.ifBlank { "第 $vmOrder 话" }
        scope.launch {
            val result = runCatching {
                val file = ImageSaver.shareToCache(context, url, comic, chapter, page + 1)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "分享图片"))
            }
            if (result.isFailure) {
                Toast.makeText(
                    context,
                    "分享失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // 翻页后复位缩放平移，避免上一页的偏移带到新页
    LaunchedEffect(pagerState.currentPage) {
        if (zoomScale <= 1f) {
            panX = 0f
            panY = 0f
        }
    }

    // 末页已读判定（P1-05）：
    // - 翻页：当前页就是最后一页且图片加载成功；
    // - 滚动：最后一行的切片进入可见区且加载成功。
    // 预取、打开章节、失败占位都不触发。
    LaunchedEffect(pagerState.currentPage, pages.size, loadedPages.size, scrollMode) {
        if (!scrollMode && pages.isNotEmpty()) {
            val last = pages.size - 1
            if (pagerState.currentPage == last && loadedPages[last] == true) {
                viewModel.markChapterRead()
            }
        }
    }
    LaunchedEffect(listState, rows.size, loadedRows.size, pages.size, scrollMode) {
        if (scrollMode && rows.isNotEmpty()) {
            snapshotFlow {
                val lastRow = rows.lastIndex
                listState.layoutInfo.visibleItemsInfo.any { it.index == lastRow }
            }.collect { visible ->
                val lastPair = rows.lastOrNull()
                if (visible && lastPair != null &&
                    loadedRows["${lastPair.first}:${lastPair.second}"] == true
                ) {
                    viewModel.markChapterRead()
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            AnimatedVisibility(visible = showPanel) {
                TopAppBar(
                    title = {
                        Text(
                            text = title.ifBlank { epTitle }.ifBlank { "第 $vmOrder 话" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCC000000)),
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = showPanel) {
                ReaderControlPanel(
                    modifier = Modifier.navigationBarsPadding(),
                    scrollMode = scrollMode,
                    onModeChange = { mode ->
                        if (mode != scrollMode) {
                            val target = currentPage
                            scrollMode = mode
                            ReaderPrefs.current().readerMode = if (mode) 0 else 1
                            scope.launch {
                                if (mode) {
                                    listState.scrollToItem(rowForPage(target))
                                } else {
                                    pagerState.scrollToPage(
                                        target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                                    )
                                }
                            }
                        }
                    },
                    brightness = brightness,
                    onBrightnessChange = {
                        brightness = it
                        ReaderPrefs.current().brightness = it
                    },
                    currentPage = currentPage + 1,
                    totalPages = pages.size,
                    onPageSeek = ::seekToPage,
                    hasPrev = currentChapterIndex > 0,
                    hasNext = currentChapterIndex in 0 until sortedChapters.size - 1,
                    onPrevChapter = {
                        sortedChapters.getOrNull(currentChapterIndex - 1)?.let { switchTo(it.order) }
                    },
                    onNextChapter = {
                        sortedChapters.getOrNull(currentChapterIndex + 1)?.let { switchTo(it.order) }
                    },
                    flipState = pages.getOrNull(currentPage)?.let { manualFlip[it.imageUrl] } ?: 0,
                    onToggleFlip = {
                        val u = pages.getOrNull(currentPage)?.imageUrl
                        if (u != null) {
                            manualFlip[u] = when (manualFlip[u] ?: 0) {
                                0 -> 1
                                1 -> -1
                                else -> 0
                            }
                            if (manualFlip[u] == 0) manualFlip.remove(u)
                        }
                    },
                    showChapters = showChapterButton,
                    onOpenChapters = { showChapterSheet = true },
                    chapterLabel = "第 $vmOrder 话",
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // 音量键翻页：按下/抬起都拦截（防止系统调音量），抬起时翻一页
                    if (!volumeKeyPaging) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.VolumeUp, Key.VolumeDown -> {
                            if (event.type == KeyEventType.KeyUp && zoomScale <= 1f) {
                                pageTurn(if (event.key == Key.VolumeUp) -1 else 1)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset -> onTap(offset.x, size.width) },
                        onDoubleTap = {
                            if (!scrollMode) {
                                if (zoomScale > 1f) {
                                    zoomScale = 1f
                                    panX = 0f
                                    panY = 0f
                                } else {
                                    zoomScale = 2f
                                }
                            }
                        },
                        onLongPress = { offset ->
                            if (zoomScale <= 1f && pages.isNotEmpty()) {
                                pageActionTarget = pageAtOffset(offset.y)
                                showPageActionSheet = true
                            }
                        },
                    )
                }
                // 缩放态下的捏合缩放与拖拽平移（仅翻页模式；滚动流交给 LazyColumn 处理）
                .pointerInput(scrollMode) {
                    if (scrollMode) return@pointerInput
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                        val maxX = (zoomScale - 1f) * size.width / 2f
                        val maxY = (zoomScale - 1f) * size.height / 2f
                        panX = (panX + pan.x).coerceIn(-maxX, maxX)
                        panY = (panY + pan.y).coerceIn(-maxY, maxY)
                        if (zoomScale <= 1f) {
                            panX = 0f
                            panY = 0f
                        }
                    }
                },
        ) {
            when {
                pages.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                pages.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { viewModel.load(context, ref, order) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "加载失败，点击重试",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                scrollMode -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(
                            count = rows.size,
                            key = { index ->
                                val (p, s) = rows[index]
                                "row_${p}_$s"
                            },
                        ) { index ->
                            val (pageIndex, sliceIndex) = rows[index]
                            val orderSnapshot = vmOrder
                            WebtoonSplitPage(
                                pageIndex = pageIndex,
                                imageUrl = displayUrl(pageIndex),
                                sliceIndex = sliceIndex,
                                sliceCount = sliceCounts[pageIndex] ?: 1,
                                measuredRatio = pageRatios[pageIndex],
                                viewportAspect = viewportAspect,
                                splitEnabled = true,
                                onPageMeasured = { p, ratio, n ->
                                    if (orderSnapshot == vmOrder) {
                                        val old = pageRatios[p]
                                        val prevRatio = if (old != null &&
                                            kotlin.math.abs(old - ratio) <= old * 0.005f
                                        ) ratio else old
                                        val prevCount = sliceCounts[p]
                                        if (prevRatio != ratio) pageRatios[p] = ratio
                                        if (prevCount != n) sliceCounts[p] = n
                                        if (n > 1 && (prevRatio != ratio || prevCount != n)) {
                                            com.jmread.data.WebtoonSliceCache.putAll(
                                                context, ref, orderSnapshot,
                                                mapOf(p to com.jmread.data.SliceInfo(n, ratio)),
                                            )
                                        }
                                    }
                                },
                                onLoaded = {
                                    if (orderSnapshot == vmOrder) {
                                        loadedRows["$pageIndex:$sliceIndex"] = true
                                    }
                                },
                            )
                        }
                        item(key = "reader_end_card") {
                            ReaderEndCard(
                                chapterTitle = epTitle.ifBlank { "第 $vmOrder 话" },
                                chaptersReady = sortedChapters.isNotEmpty(),
                                isLastChapter = sortedChapters.isNotEmpty() &&
                                    currentChapterIndex == sortedChapters.size - 1,
                                hasNext = currentChapterIndex in 0 until sortedChapters.size - 1,
                                nextTitle = sortedChapters.getOrNull(currentChapterIndex + 1)?.title,
                                onNextChapter = {
                                    sortedChapters.getOrNull(currentChapterIndex + 1)
                                        ?.let { switchTo(it.order) }
                                },
                                onBack = onBack,
                            )
                        }
                    }
                }

                else -> {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = zoomScale <= 1f,
                        // P2-08：正常内存前后各 1 页；低内存/压力降为 0
                        beyondViewportPageCount = MemoryBudget.pagerPrefetchPages(),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panX
                                translationY = panY
                            },
                    ) { page ->
                        if (page < pages.size) {
                            ReaderPage(
                                pageIndex = page,
                                imageUrl = displayUrl(page),
                                pageKey = "$ref:$vmOrder:$page:${displayUrl(page)}",
                                onLoaded = { loadedPages[page] = true },
                                onError = { loadedPages.remove(page) },
                            )
                        } else {
                            ReaderEndCard(
                                chapterTitle = epTitle.ifBlank { "第 $vmOrder 话" },
                                chaptersReady = sortedChapters.isNotEmpty(),
                                isLastChapter = sortedChapters.isNotEmpty() &&
                                    currentChapterIndex == sortedChapters.size - 1,
                                hasNext = currentChapterIndex in 0 until sortedChapters.size - 1,
                                nextTitle = sortedChapters.getOrNull(currentChapterIndex + 1)?.title,
                                onNextChapter = {
                                    sortedChapters.getOrNull(currentChapterIndex + 1)
                                        ?.let { switchTo(it.order) }
                                },
                                onBack = onBack,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // 亮度蒙层
            if (brightness < 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.8f))),
                )
            }

            // 首次点击分区引导：一次性，点任意处关闭
            if (pages.isNotEmpty() && !guideShown && !guideDismissed) {
                ReaderZoneGuideOverlay(
                    scrollMode = scrollMode,
                    onDismiss = {
                        guideDismissed = true
                        scope.launch { ReaderPrefs.current().setReaderGuideShown(true) }
                    },
                )
            }
        }
    }

    // ── 章节列表抽屉（P0-01） ───────────────────────────────────────────────
    if (showChapterSheet) {
        val sheetListState = rememberLazyListState()
        val displayChapters = if (chapterDesc) sortedChapters.asReversed() else sortedChapters
        val isOnline = rememberIsOnline()
        LaunchedEffect(showChapterSheet, displayChapters, chapterDesc) {
            val index = displayChapters.indexOfFirst { it.order == vmOrder }
            if (index >= 0) sheetListState.scrollToItem((index - 1).coerceAtLeast(0))
        }
        ModalBottomSheet(
            onDismissRequest = { showChapterSheet = false },
            containerColor = Color(0xFF161616),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "章节（${sortedChapters.size}）",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                ReaderPrefs.current().setChapterListDescending(!chapterDesc)
                            }
                        },
                    ) {
                        Text(
                            text = if (chapterDesc) "倒序 ↓" else "正序 ↑",
                            color = Color.White,
                        )
                    }
                }
                if (displayChapters.isEmpty()) {
                    Text(
                        text = "章节列表未就绪 · 点击重试",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.retryChapters()
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                LazyColumn(
                    state = sheetListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    items(displayChapters, key = { it.id.ifBlank { "order:${it.order}" } }) { chapter ->
                        val isCurrent = chapter.order == vmOrder
                        val isRead = if (chapter.id.isNotBlank()) {
                            chapter.id in readChapterKeys
                        } else {
                            ChaptersReadDoc.orderKey(chapter.order) in readChapterKeys
                        }
                        val isDownloaded = chapter.order in downloadedOrders
                        val canOpen = isOnline || isDownloaded
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canOpen) {
                                    switchTo(chapter.order)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = chapter.title.ifBlank { "第 ${chapter.order} 话" },
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else if (canOpen) {
                                        Color.White
                                    } else {
                                        Color.Gray
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!canOpen) {
                                    Text(
                                        text = "未下载，联网后可读",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            if (isCurrent) {
                                Text(
                                    text = "当前",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            if (isRead) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已读",
                                    tint = Color(0xFF8A8A8A),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            if (isDownloaded) {
                                Text(
                                    text = "已下载",
                                    color = Color(0xFF8A8A8A),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 长按页操作菜单：保存到相册 / 分享此页（P1-04） ────────────────────
    if (showPageActionSheet && pageActionTarget >= 0) {
        val actionPage = pageActionTarget
        ModalBottomSheet(
            onDismissRequest = { showPageActionSheet = false },
            containerColor = Color(0xFF161616),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Text(
                    text = "第 ${actionPage + 1} 页",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showPageActionSheet = false
                            saveWithPermission(actionPage)
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.SaveAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("保存到相册", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showPageActionSheet = false
                            sharePage(actionPage)
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("分享此页", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.size(12.dp))
            }
        }
    }
}

/** 横滑翻页单页：加载中 / 失败重试 / 成功渲染（P0-02 / P0-03 / P2-08）。 */
@Composable
private fun ReaderPage(
    pageIndex: Int,
    imageUrl: String,
    pageKey: String,
    onLoaded: () -> Unit,
    onError: (Throwable?) -> Unit,
) {
    val context = LocalContext.current
    // 重试计数随页身份重置；重试后缀放在 #m1/#m0 之前，不进入持久缓存身份
    var retryTick by remember(pageKey) { mutableIntStateOf(0) }
    val manualSuffix = when {
        imageUrl.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> ImageScrambler.SUFFIX_FORCE_RESTORE
        imageUrl.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> ImageScrambler.SUFFIX_FORCE_RAW
        else -> ""
    }
    val base = imageUrl.removeSuffix(manualSuffix)
    val data = if (retryTick == 0) imageUrl else "$base#retry$retryTick$manualSuffix"
    val cacheKey = jmImageCacheKey(imageUrl)
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(data)
            .size(pagerDecodeSize(context))
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .crossfade(false)
            .build(),
    )
    val state = painter.state
    LaunchedEffect(state) {
        when (val s = state) {
            is AsyncImagePainter.State.Success -> onLoaded()
            is AsyncImagePainter.State.Error -> {
                val t = s.result.throwable
                val host = runCatching { android.net.Uri.parse(imageUrl).host }.getOrNull()
                android.util.Log.e(
                    "ReaderPage",
                    "page=${pageIndex + 1} ${t.javaClass.simpleName}: ${t.message} host=$host",
                )
                onError(t)
            }
            else -> {}
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            is AsyncImagePainter.State.Loading -> {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            }

            is AsyncImagePainter.State.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "第 ${pageIndex + 1} 页加载失败",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "点击重试",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { retryTick++ },
                    )
                }
            }

            else -> {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = "第 ${pageIndex + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 首次点击分区引导（一次性）：按当前阅读模式给不同文案。 */
@Composable
private fun ReaderZoneGuideOverlay(
    scrollMode: Boolean,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = "阅读指引",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(28.dp))
            if (scrollMode) {
                GuideZone("上下滑动", "阅读 / 翻屏", Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth()) {
                    GuideZone("左侧 30%", "上一页", Modifier.weight(0.3f))
                    GuideZone("中间 40%", "呼出菜单", Modifier.weight(0.4f))
                    GuideZone("右侧 30%", "下一页", Modifier.weight(0.3f))
                }
            }
            Spacer(Modifier.size(28.dp))
            Text(
                text = "双击可缩放 · 长按可保存/分享",
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.size(20.dp))
            Text(
                text = "点击任意位置开始阅读",
                color = Color(0xFFBDBDBD),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun GuideZone(label: String, action: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = Color(0xFFBDBDBD),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = action,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** 阅读器底部控制面板：亮度 / 页码 / 模式 / 翻转 / 章节 / 上一话·下一话。 */
@Composable
private fun ReaderControlPanel(
    modifier: Modifier = Modifier,
    scrollMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    currentPage: Int,
    totalPages: Int,
    onPageSeek: (Int) -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    flipState: Int = 0,
    onToggleFlip: () -> Unit = {},
    showChapters: Boolean = true,
    onOpenChapters: () -> Unit = {},
    chapterLabel: String = "",
) {
    Surface(
        modifier = modifier,
        color = Color(0xE6000000),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BrightnessMedium,
                    contentDescription = "亮度",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.2f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(brightness * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (totalPages > 1) {
                var dragging by remember { mutableStateOf(false) }
                var sliderPos by remember {
                    mutableFloatStateOf((currentPage - 1).coerceIn(0, totalPages - 1).toFloat())
                }
                LaunchedEffect(currentPage, totalPages) {
                    if (!dragging) {
                        sliderPos = (currentPage - 1).coerceIn(0, totalPages - 1).toFloat()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "页码",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(32.dp),
                    )
                    Slider(
                        value = sliderPos,
                        onValueChange = {
                            dragging = true
                            sliderPos = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            onPageSeek(sliderPos.toInt().coerceIn(0, totalPages - 1))
                        },
                        valueRange = 0f..(totalPages - 1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${sliderPos.toInt() + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onModeChange(false) }) {
                    Icon(
                        Icons.Filled.ViewCarousel,
                        contentDescription = "横滑翻页",
                        tint = if (scrollMode) Color.Gray else Color.White,
                    )
                }
                Text(
                    text = if (scrollMode) "滚动流" else "翻页",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(onClick = { onModeChange(true) }) {
                    Icon(
                        Icons.Filled.ViewAgenda,
                        contentDescription = "滚动流",
                        tint = if (scrollMode) Color.White else Color.Gray,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleFlip) {
                    Icon(
                        Icons.Filled.Flip,
                        contentDescription = "翻转本页横条",
                        tint = if (flipState == 0) Color.White else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = when (flipState) {
                        1 -> "已倒序"
                        -1 -> "原样"
                        else -> "自动"
                    },
                    color = if (flipState == 0) Color.Gray else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (showChapters) {
                    IconButton(onClick = onOpenChapters) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "章节列表",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = "章节",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一话",
                        tint = if (hasPrev) Color.White else Color.Gray,
                    )
                }
                Text(
                    text = if (chapterLabel.isBlank()) {
                        "$currentPage / $totalPages"
                    } else {
                        "$chapterLabel · $currentPage / $totalPages"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onNextChapter, enabled = hasNext) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一话",
                        tint = if (hasNext) Color.White else Color.Gray,
                    )
                }
            }
        }
    }
}

/** 章节末尾卡片：本话/全本读完 + 下一话/返回详情；不参与页码与进度 */
@Composable
private fun ReaderEndCard(
    chapterTitle: String,
    chaptersReady: Boolean,
    isLastChapter: Boolean,
    hasNext: Boolean,
    nextTitle: String?,
    onNextChapter: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (chaptersReady && isLastChapter) "全本已读完" else "本话已读完",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = chapterTitle,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(20.dp))
            when {
                !chaptersReady -> Text(
                    text = "章节列表未就绪，返回详情重试",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )

                hasNext -> {
                    Text(
                        text = nextTitle?.takeIf { it.isNotBlank() } ?: "下一话",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onNextChapter) { Text("下一话") }
                }

                else -> Text(
                    text = "已读到最后一话",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onBack) {
                Text("返回详情", color = Color.White)
            }
        }
    }
}

/** 默认网络是否有互联网连接（章节抽屉离线禁用未下载章节）。 */
@Composable
private fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isNetworkAvailable(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = isNetworkAvailable(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                online = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
