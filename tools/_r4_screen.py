import io, re

p = "app/src/main/java/com/jmread/ui/author/AuthorScreen.kt"
t = io.open(p, encoding="utf-8").read()

# 1) collect 新状态
old = """    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()"""
new = """    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    // 片段切换（对标 PiKA v1.10.0）：原署名解析片段 + 当前生效查询
    val parsed by viewModel.parsed.collectAsState()
    val currentQuery by viewModel.currentQuery.collectAsState()"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

# 2) 收藏与复制以当前查询为准（片段即身份）
old = """                    IconButton(onClick = {
                        if (favourited) {
                            com.jmread.data.AuthorFavourites.remove(author)
                        } else {
                            com.jmread.data.AuthorFavourites.add(
                                author = author,
                                coverUrl = comics.firstOrNull()?.coverUrl.orEmpty(),
                            )
                        }
                        favourited = !favourited
                    }) {"""
new = """                    IconButton(onClick = {
                        // 片段即身份：收藏/取消收藏以当前浏览的署名（片段）为准
                        val favKey = currentQuery.ifBlank { author }
                        if (favourited) {
                            com.jmread.data.AuthorFavourites.remove(favKey)
                        } else {
                            com.jmread.data.AuthorFavourites.add(
                                author = favKey,
                                coverUrl = comics.firstOrNull()?.coverUrl.orEmpty(),
                            )
                        }
                        favourited = !favourited
                    }) {"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

old = """    fun copyAuthorName() {
        copyScope.launch { snackbarHostState.showSnackbar(copyToClipboard(copyContext, "作者名", author)) }
    }"""
new = """    fun copyAuthorName() {
        val name = currentQuery.ifBlank { author }
        copyScope.launch { snackbarHostState.showSnackbar(copyToClipboard(copyContext, "作者名", name)) }
    }"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

# 3) 片段切换条：插在排序 chips 行之前
old = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(supportedSorts, key = { it.name }) { s ->"""
new = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 片段切换条（对标 PiKA v1.10.0）：点片段即按该片段重搜，选错可原地换人
            if ((parsed?.segments?.size ?: 0) > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val segments = parsed?.segments.orEmpty()
                    items(segments.size + 1, key = { "seg-$it" }) { idx ->
                        val isFull = idx == segments.size
                        val segText = if (isFull) author else segments[idx].text
                        val segLabel = if (isFull) {
                            "完整署名"
                        } else {
                            val role = segments[idx].role
                            if (role == com.jmread.core.model.AuthorRole.UNKNOWN) segText else "${role.label}·${segText}"
                        }
                        FilterChip(
                            selected = segText == currentQuery,
                            onClick = {
                                viewModel.switchAuthor(segText)
                                listState.requestScrollToItem(0)
                            },
                            label = { Text(segLabel, maxLines = 1) },
                        )
                    }
                }
                if (currentQuery.isNotBlank() && currentQuery != author) {
                    Text(
                        text = "原署名：$author",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(supportedSorts, key = { it.name }) { s ->"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

# imports：items(Int) 需要 lazy.list.items 重载（已有 items 列表重载）；补 import
if "import androidx.compose.foundation.lazy.LazyRow\n" in t and "import androidx.compose.foundation.lazy.rememberLazyListState" not in t:
    pass
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("screen ok")
