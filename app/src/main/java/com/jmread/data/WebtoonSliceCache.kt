package com.jmread.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 条漫长图的「单页切片记录」：切几屏 + 图片实测高宽比。
 *
 * 高宽比才是渲染正确性的关键：同一页的所有切片必须用**同一个**比例换算高度与偏移，
 * 否则后组合出来的切片会先用「切片数 × 视口比」占位、再各自测出真值，
 * 于是一页之内相邻切片按不同比例摆放，内容互相错位重叠（0.3.0 的条漫错位根因）。
 * 比例一旦持久化，重进本章首帧就能按真实比例铺开，无需再经历占位值阶段。
 */
data class SliceInfo(
    /** 该页切分为几屏（仅用于 LazyColumn 分块粒度与页码换算，不影响总高度） */
    val count: Int,
    /** 图片「高/宽」比；旧版本缓存没有这一项，为 null，需等图片加载后补测 */
    val ratio: Float? = null,
)

/**
 * 条漫长图切片记录缓存（磁盘持久化，按章稀疏存储，只记 >1 屏的页）。
 *
 * 滚动流的"页码 → 行号"换算依赖每页切片数，而切片数与比例只有图片实际加载后才能测得；
 * 不缓存的话每次进度恢复都按 1 屏估算，全分屏长图章节会系统性定位偏早，
 * 且远端页永不组合、无法靠现场解析自愈。缓存后第二次进入即可按真实行号定位。
 *
 * 键为 `作品id#章号`。本缓存可重建：改键格式后旧条目只是不命中，
 * 相当于重新测一次切片数，不产生错误数据，故不做迁移。
 *
 * 序列化格式：`页码:切片数[:高宽比]`，逗号分隔。第三段为 0.3.0 之后新增，
 * 旧数据只有两段，按「无比例」解析（等图片加载后补测）。
 *
 * PERF-08：测量回调此前在主线程逐页 `putAll`（merge + SP 序列化），快速滚动时
 * 每屏一次。现在写入先进内存缓冲，由单写者 IO 协程合并约 400ms 的变化，
 * 一章一次序列化；`flushNow()` 在切章/退出时按**旧章节身份**立即提交，
 * 不会把旧结果写进新章。
 */
object WebtoonSliceCache {

    private const val PREFS = "jm_webtoon_slices"
    private const val MAX_CHAPTERS = 512

    /** 批量提交的去抖窗口 */
    private const val FLUSH_DEBOUNCE_MS = 400L

    private val map = java.util.concurrent.ConcurrentHashMap<String, Map<Int, SliceInfo>>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // ---------- 单写者批量提交（PERF-08） ----------

    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushRequests = MutableStateFlow(0L)
    private val pending = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, MutableMap<Int, SliceInfo>>()
    @Volatile private var appContext: Context? = null

    /** 由 JmApp.onCreate 调用；未调用时批量写入静默失效（只丢缓存，可重建） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val flusher = flushScope.launch {
        flushRequests.collectLatest {
            delay(FLUSH_DEBOUNCE_MS)
            doFlush()
        }
    }

    /** 合并一批测量结果（主线程调用只做内存合并，IO 由单写者完成） */
    fun putBatched(ref: String, order: Int, infos: Map<Int, SliceInfo>) {
        val useful = infos.filterValues { it.count > 1 }
        if (useful.isEmpty()) return
        val bucket = pending.getOrPut(ref to order) { java.util.concurrent.ConcurrentHashMap() }
        bucket.putAll(useful)
        flushRequests.value = flushRequests.value + 1
    }

    /** 切章/退出阅读器时立即提交（按 pending 里记录的旧章节身份，不受当前章影响） */
    fun flushNow() {
        if (pending.isEmpty()) return
        flushScope.launch { doFlush() }
    }

    private suspend fun doFlush() {
        val context = appContext ?: return
        if (pending.isEmpty()) return
        val snapshot = HashMap(pending)
        pending.clear()
        for ((identity, infos) in snapshot) {
            val (ref, order) = identity
            putAll(context, ref, order, infos)
        }
    }

    // ---------- 存取 ----------

    private fun sp(context: Context): android.content.SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }

    private fun key(ref: String, order: Int) = "$ref#$order"

    fun load(ref: String, order: Int, context: Context): Map<Int, SliceInfo> {
        val key = key(ref, order)
        map[key]?.let { return it }
        val raw = runCatching { sp(context).getString(key, null) }.getOrNull() ?: return emptyMap()
        val parsed = raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val page = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val n = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val ratio = parts.getOrNull(2)?.toFloatOrNull()?.takeIf { it > 0f }
            if (page < 0 || n < 2) null else page to SliceInfo(n, ratio)
        }.toMap()
        map[key] = parsed
        return parsed
    }

    fun putAll(context: Context, ref: String, order: Int, infos: Map<Int, SliceInfo>) {
        val store = infos.filterValues { it.count > 1 }
        if (store.isEmpty()) return
        val key = key(ref, order)
        val merged = load(ref, order, context) + store
        map[key] = merged
        runCatching {
            sp(context).edit()
                .putString(
                    key,
                    merged.entries.joinToString(",") { (page, info) ->
                        // Float.toString 固定用 '.' 作小数点，不受系统区域设置影响
                        val ratio = info.ratio?.let { ":$it" } ?: ""
                        "$page:${info.count}$ratio"
                    },
                )
                .apply()
        }
        // 容量护栏：极端使用下避免无限增长（每章一条稀疏记录，512 章远超常规用量）
        if (map.size > MAX_CHAPTERS) {
            map.keys.firstOrNull()?.let { stale ->
                map.remove(stale)
                runCatching { sp(context).edit().remove(stale).apply() }
            }
        }
    }

    /** 已缓存章数（清除缓存入口的体积展示用） */
    fun chapterCount(): Int = map.size

    /** 一键清空（设置页「清除缓存」）：内存表与 SP 全清。缓存可重建，无用户数据 */
    fun clear(context: Context) {
        pending.clear()
        map.clear()
        runCatching { sp(context).edit().clear().apply() }
    }
}
