# -*- coding: utf-8 -*-
"""阅读器手动翻转兜底：ImageScrambler 强制覆盖 + Fetcher/Keyer 后缀协议 + Reader UI"""
import io

# ── 1. ImageScrambler：manual override ──────────────────────────────
p = 'app/src/main/java/com/jmread/core/scramble/ImageScrambler.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''object ImageScrambler {

    /** 是否是需要判定乱序的图片 URL */
    fun isPhotoUrl(url: String): Boolean = url.contains("/media/photos/")''',
'''object ImageScrambler {

    /** 手动覆盖后缀：强制倒序还原（自动判定漏判时用户兜底） */
    const val SUFFIX_FORCE_RESTORE = "#m1"
    /** 手动覆盖后缀：强制保持原样（自动判定误翻时用户兜底） */
    const val SUFFIX_FORCE_RAW = "#m0"

    // 手动覆盖表（内存态）：1=强制还原 / -1=强制原样；键为干净 URL
    private val manualOverride = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun setManualOverride(cleanUrl: String, value: Int?) {
        if (value == null || value == 0) manualOverride.remove(cleanUrl) else manualOverride[cleanUrl] = value
    }

    fun manualOverrideOf(cleanUrl: String): Int = manualOverride[cleanUrl] ?: 0

    /** 从带覆盖后缀的 URL 中剥出干净 URL（#m1 / #m0） */
    fun stripManualSuffix(data: String): String = when {
        data.endsWith(SUFFIX_FORCE_RESTORE) -> data.removeSuffix(SUFFIX_FORCE_RESTORE)
        data.endsWith(SUFFIX_FORCE_RAW) -> data.removeSuffix(SUFFIX_FORCE_RAW)
        else -> data
    }

    /** 是否是需要判定乱序的图片 URL */
    fun isPhotoUrl(url: String): Boolean = url.contains("/media/photos/")''')

s = s.replace('''    /** 判定 + 条件还原。未命中打乱时原样返回 [bitmap]（不复制位图） */
    fun processIfNeeded(url: String, bitmap: Bitmap): Bitmap {
        if (!isPhotoUrl(url) || bitmap.width <= 0 || bitmap.height < ScrambleDetector.candidateSplits.last()) {
            return bitmap
        }
        val key = verdictKey(url)
        if (ScrambleCache.get(key) == false) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val (restored, scrambled) = ScrambleDetector.detectAndRestore(pixels, w, h)
        ScrambleCache.put(key, scrambled)
        if (!scrambled) return bitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(restored, 0, w, 0, 0, w, h)
        return out
    }''',
'''    /** 判定 + 条件还原。未命中打乱时原样返回 [bitmap]（不复制位图）。
     *  [force]：1=强制还原 / -1=强制原样 / null=自动判定（写入判定缓存） */
    fun processIfNeeded(url: String, bitmap: Bitmap, force: Int? = null): Bitmap {
        if (!isPhotoUrl(url) || bitmap.width <= 0 || bitmap.height < ScrambleDetector.candidateSplits.last()) {
            return bitmap
        }
        val key = verdictKey(url)
        if (force == null && ScrambleCache.get(key) == false) return bitmap
        if (force == -1) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (force == 1) {
            val restored = ScrambleDetector.restore(pixels, w, h, 10)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(restored, 0, w, 0, 0, w, h)
            return out
        }
        val (restored, scrambled) = ScrambleDetector.detectAndRestore(pixels, w, h)
        ScrambleCache.put(key, scrambled)
        if (!scrambled) return bitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(restored, 0, w, 0, 0, w, h)
        return out
    }''')

s = s.replace('''    fun processBytesIfNeeded(url: String, bytes: ByteArray): ByteArray {
        if (!isPhotoUrl(url)) return bytes
        val key = verdictKey(url)
        if (ScrambleCache.get(key) == false) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val processed = processIfNeeded(url, bmp)
        if (processed === bmp) {''',
'''    fun processBytesIfNeeded(url: String, bytes: ByteArray, force: Int? = null): ByteArray {
        if (!isPhotoUrl(url)) return bytes
        if (force == -1) return bytes
        val key = verdictKey(url)
        if (force == null && ScrambleCache.get(key) == false) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val processed = processIfNeeded(url, bmp, force)
        if (processed === bmp) {''')

io.open(p, 'w', encoding='utf-8').write(s)

# ── 2. Fetcher：识别强制后缀 ────────────────────────────────────────
p = 'app/src/main/java/com/jmread/core/scramble/JmImageFetcher.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''class JmUrlKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String =
        if (ImageScrambler.isPhotoUrl(data)) "$data#de" else data
}''',
'''class JmUrlKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String = when {
        // 手动覆盖请求自带独立后缀，直接作 key（与自动判定结果缓存隔离）
        data.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) ||
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> data
        ImageScrambler.isPhotoUrl(data) -> "$data#de"
        else -> data
    }
}''')
s = s.replace('''    override suspend fun fetch(): coil.fetch.FetchResult = withContext(Dispatchers.IO) {
        val bytes = download(data)
        val processed = if (ImageScrambler.isPhotoUrl(data)) {
            ImageScrambler.processBytesIfNeeded(data, bytes)
        } else {
            bytes
        }''',
'''    override suspend fun fetch(): coil.fetch.FetchResult = withContext(Dispatchers.IO) {
        val force = when {
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> 1
            data.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> -1
            else -> null
        }
        val cleanUrl = ImageScrambler.stripManualSuffix(data)
        val bytes = download(cleanUrl)
        val processed = if (ImageScrambler.isPhotoUrl(cleanUrl)) {
            ImageScrambler.processBytesIfNeeded(cleanUrl, bytes, force)
        } else {
            bytes
        }''')
io.open(p, 'w', encoding='utf-8').write(s)

# ── 3. ReaderScreen：翻转状态 + 展示 URL + 面板按钮 ─────────────────
p = 'app/src/main/java/com/jmread/ui/reader/ReaderScreen.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''    var scrollMode by remember { mutableStateOf(ReaderPrefs.current().readerMode == 0) }
    var showPanel by remember { mutableStateOf(false) }''',
'''    var scrollMode by remember { mutableStateOf(ReaderPrefs.current().readerMode == 0) }
    var showPanel by remember { mutableStateOf(false) }
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
    }''')

s = s.replace('''                            WebtoonSplitPage(
                                pageIndex = pageIndex,
                                imageUrl = pages[pageIndex].imageUrl,''',
'''                            WebtoonSplitPage(
                                pageIndex = pageIndex,
                                imageUrl = displayUrl(pageIndex),''')

s = s.replace('''                    ) { page ->
                        AsyncImage(
                            model = pages[page].imageUrl,
                            contentDescription = "第 ${page + 1} 页",''',
'''                    ) { page ->
                        AsyncImage(
                            model = displayUrl(page),
                            contentDescription = "第 ${page + 1} 页",''')

s = s.replace('''                    onPrevChapter = {
                        sortedChapters.getOrNull(currentChapterIndex - 1)?.let { switchTo(it.order) }
                    },
                    onNextChapter = {
                        sortedChapters.getOrNull(currentChapterIndex + 1)?.let { switchTo(it.order) }
                    },
                )''',
'''                    onPrevChapter = {
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
                )''')

s = s.replace('''    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {''',
'''    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    /** 当前页手动翻转态：0=自动 / 1=强制倒序 / -1=强制原样 */
    flipState: Int = 0,
    onToggleFlip: () -> Unit = {},
) {''')

s = s.replace('''                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {''',
'''                Spacer(Modifier.weight(1f))
                // 手动翻转本页横条（自动判定的兜底）：自动 → 强制倒序 → 强制原样 循环
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
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {''')

if 'import androidx.compose.material.icons.filled.Flip' not in s:
    s = s.replace('import androidx.compose.material.icons.filled.BrightnessMedium',
                  'import androidx.compose.material.icons.filled.BrightnessMedium\nimport androidx.compose.material.icons.filled.Flip')

io.open(p, 'w', encoding='utf-8').write(s)
print("done")
