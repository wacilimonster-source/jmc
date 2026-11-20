import io, re

p = "app/src/main/java/com/jmread/ui/author/AuthorScreen.kt"
t = io.open(p, encoding="utf-8").read()

# imports
anchor = "import androidx.compose.material.icons.outlined.FavoriteBorder\n"
add = (
    "import androidx.compose.foundation.combinedClickable\n"
    "import androidx.compose.material.icons.filled.ContentCopy\n"
    "import androidx.compose.material3.SnackbarHost\n"
    "import androidx.compose.material3.SnackbarHostState\n"
    "import androidx.compose.runtime.rememberCoroutineScope\n"
    "import androidx.compose.ui.platform.LocalContext\n"
    "import com.jmread.ui.common.copyToClipboard\n"
    "import kotlinx.coroutines.launch\n"
)
assert anchor in t
t = t.replace(anchor, anchor + add, 1)

# scaffold: 加 snackbarHost；title 长按复制；actions 加复制图标
old_scaffold = """    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {"""
new_scaffold = """    // 复制反馈（对标 PiKA v1.12.0：作者页复制图标 + 标题长按，Snackbar 统一反馈）
    val copyScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copyContext = LocalContext.current
    fun copyAuthorName() {
        copyScope.launch { snackbarHostState.showSnackbar(copyToClipboard(copyContext, "作者名", author)) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { copyAuthorName() }),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { copyAuthorName() }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制作者名")
                    }
                    IconButton(onClick = {"""
assert t.count(old_scaffold) == 1
t = t.replace(old_scaffold, new_scaffold, 1)

io.open(p, "w", encoding="utf-8", newline="").write(t)
print("author ok")
