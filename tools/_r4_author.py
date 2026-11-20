import io, re

p = "app/src/main/java/com/jmread/ui/author/AuthorViewModel.kt"
t = io.open(p, encoding="utf-8").read()

# 片段状态 + 切换查询
old = """    private var _author: String = ""
"""
new = """    private var _author: String = ""

    /** 原始署名（路由参数，展示/兜底用；片段搜索不改变它） */
    private val _origin = MutableStateFlow("")
    val origin: StateFlow<String> = _origin.asStateFlow()

    /** 当前生效查询（片段或完整署名；收藏/复制以它为准 —— 对标 PiKA v1.10.0「片段即身份」） */
    private val _currentQuery = MutableStateFlow("")
    val currentQuery: StateFlow<String> = _currentQuery.asStateFlow()

    /** 原署名的解析片段（作者页顶部切换条数据源） */
    private val _parsed = MutableStateFlow<ParsedAuthor?>(null)
    val parsed: StateFlow<ParsedAuthor?> = _parsed.asStateFlow()

    /** 切换到某个署名片段（或完整署名）重新搜索 */
    fun switchAuthor(query: String) {
        if (query == _currentQuery.value) return
        _currentQuery.value = query
        _needsScrollRestore = false
        loadComics(query, page = 1)
    }
"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

old2 = """    fun loadComics(author: String, page: Int) {
        _author = author
"""
new2 = """    fun loadComics(author: String, page: Int) {
        _author = author
        if (page == 1 && _currentQuery.value != author) {
            _currentQuery.value = author
        }
        if (_origin.value != author && _currentQuery.value == author) {
            _origin.value = author
            _parsed.value = AuthorName.parse(author)
        }
"""
assert t.count(old2) == 1
t = t.replace(old2, new2, 1)

# import
anchor = "import com.jmread.core.model.ComicSort\n"
assert anchor in t
t = t.replace(anchor, "import com.jmread.core.model.AuthorName\nimport com.jmread.core.model.ComicSort\n", 1)
if "import com.jmread.core.model.ParsedAuthor" not in t:
    t = t.replace("import com.jmread.core.model.ComicSummary\n",
                  "import com.jmread.core.model.ComicSummary\nimport com.jmread.core.model.ParsedAuthor\n", 1)

io.open(p, "w", encoding="utf-8", newline="").write(t)
print("viewmodel ok")
