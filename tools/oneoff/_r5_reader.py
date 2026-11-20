import io

p = "app/src/main/java/com/jmread/ui/reader/ReaderScreen.kt"
t = io.open(p, encoding="utf-8").read()

# 1) 重组热点①：userScrollEnabled 布尔化（derivedStateOf）
old = "    var zoomScale by remember { mutableFloatStateOf(1f) }"
new = """    var zoomScale by remember { mutableFloatStateOf(1f) }
    // PERF：捏合期间每帧都会改 zoomScale，但 pager 只关心「是否可翻页」这个布尔——
    // 用 derivedStateOf 把高频 Float 变化收敛成布尔翻转，避免整条 pager 分支每帧重组
    val pagerScrollEnabled = remember { derivedStateOf { zoomScale <= 1f } }"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

old = "                        userScrollEnabled = zoomScale <= 1f,"
new = "                        userScrollEnabled = pagerScrollEnabled.value,"
assert t.count(old) == 1
t = t.replace(old, new, 1)

# 2) 重组热点②：亮度蒙层拆叶子组件
old = """            // 亮度蒙层
            if (brightness < 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.8f))),
                )
            }"""
new = """            // 亮度蒙层：状态读取下沉到叶子 Composable——滑条每帧变化只重组蒙层本身，
            // 不再重组包含 LazyColumn/Pager 的整个内容作用域（PERF，对标 pika perf-arch P1-8）
            BrightnessScrim(alpha = { 1f - brightness })"""
assert t.count(old) == 1
t = t.replace(old, new, 1)

# 3) 叶子组件追加到文件尾
if "private fun BrightnessScrim" not in t:
    t = t.rstrip() + """

/**
 * 亮度蒙层（叶子）：只在自身组合作用域读亮度值，拖动亮度滑条时不触发
 * 阅读器内容（LazyColumn/Pager）的任何重组。
 */
@Composable
private fun BrightnessScrim(alpha: () -> Float) {
    val a = alpha().coerceIn(0f, 0.8f)
    if (a > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = a)),
        )
    }
}
"""

# 4) derivedStateOf import
if "import androidx.compose.runtime.derivedStateOf" not in t:
    anchor = "import androidx.compose.runtime.getValue\n"
    assert anchor in t
    t = t.replace(anchor, "import androidx.compose.runtime.derivedStateOf\n" + anchor, 1)

io.open(p, "w", encoding="utf-8", newline="").write(t)
print("reader ok")
