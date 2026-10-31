package com.jmread.data

import android.content.Context

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
 */
object WebtoonSliceCache {

    private const val PREFS = "jm_webtoon_slices"
    private const val MAX_CHAPTERS = 512

    private val map = java.util.concurrent.ConcurrentHashMap<String, Map<Int, SliceInfo>>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

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
        map.clear()
        runCatching { sp(context).edit().clear().apply() }
    }
}
