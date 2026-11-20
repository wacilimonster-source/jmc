import io, re

def patch(path, pairs, add_imports=()):
    p = "app/src/main/java/com/jmread/" + path
    t = io.open(p, encoding="utf-8").read()
    for old, new, expected in pairs:
        n = t.count(old)
        assert n == expected, f"{path}: {n} occurrences (want {expected}) of {old[:70]!r}"
        t = t.replace(old, new)
    for imp in add_imports:
        if imp not in t:
            t = re.sub(r"^(package [^\n]+)$", r"\1\n\n" + imp, t, count=1, flags=re.M)
    io.open(p, "w", encoding="utf-8", newline="").write(t)
    print("patched", path)

# ---------- 详情页 ----------
# 1) 顶栏「更多」菜单：复制作品名 / 复制作者名（对标 PiKA v1.12.0 P0）
patch("ui/detail/ComicDetailScreen.kt", [
 ("""                actions = {
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
                },
                windowInsets = WindowInsets(0, 0),
            )
        },""",
  """                actions = {
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
        },""", 1),
])

# 2) 标题/作者行长按复制（点按语义不变）
patch("ui/detail/ComicDetailScreen.kt", [
 ("""        Column(Modifier.fillMaxWidth()) {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
                            modifier = Modifier.clickable { onOpenAuthor(a) },
                        )
                    }
                }
            }""",
  """        Column(Modifier.fillMaxWidth()) {
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
                            // 点按进作者页（语义不变）；长按复制该作者名
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenAuthor(a) },
                                onLongClick = { onCopyAuthor(a) },
                            ),
                        )
                    }
                }
            }""", 1),
])

# 3) ComicHeader 签名加复制回调，调用处传入
patch("ui/detail/ComicDetailScreen.kt", [
 ("""private fun ComicHeader(
    comic: ComicDetail,
    favourited: Boolean,
    onOpenAuthor: (String) -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {""",
  """private fun ComicHeader(
    comic: ComicDetail,
    favourited: Boolean,
    onOpenAuthor: (String) -> Unit,
    onCopyTitle: () -> Unit = {},
    onCopyAuthor: (String) -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
) {""", 1),
 ("""                    ComicHeader(
                        comic = c,
                        favourited = favourited,
                        onOpenAuthor = onOpenAuthor,
                        actions = if (landscape) detailActions else null,
                    )""",
  """                    ComicHeader(
                        comic = c,
                        favourited = favourited,
                        onOpenAuthor = onOpenAuthor,
                        onCopyTitle = { scope.launch { snackbarHostState.showSnackbar(com.jmread.ui.common.copyToClipboard(context, "作品名", c.title)) } },
                        onCopyAuthor = { a -> scope.launch { snackbarHostState.showSnackbar(com.jmread.ui.common.copyToClipboard(context, "作者名", a)) } },
                        actions = if (landscape) detailActions else null,
                    )""", 1),
])

# 4) ON_RESUME 续读刷新（对标 PiKA v1.12.3-3）：DisposableEffect + LifecycleEventObserver
patch("ui/detail/ComicDetailScreen.kt", [
 ("""    LaunchedEffect(ref) {
        viewModel.load(ref)
        // 从阅读器返回时页面会被重新组合：顺手刷新「继续阅读」进度
        viewModel.refreshProgress()
    }""",
  """    LaunchedEffect(ref) {
        viewModel.load(ref)
        // 从阅读器返回时页面会被重新组合：顺手刷新「继续阅读」进度
        viewModel.refreshProgress()
    }
    // ON_RESUME 兜底刷新（对标 PiKA v1.12.3-3）：无论组合是否重建，回到前台/返回详情
    // 都立即刷新本地进度，「继续阅读」按钮与上次读到的话/页保持实时
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshProgress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }""", 1),
])
print("DETAIL OK")
