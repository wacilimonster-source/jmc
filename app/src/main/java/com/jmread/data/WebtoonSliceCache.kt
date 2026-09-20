package com.jmread.data

import android.content.Context

/**
 * 条漫长图切片数缓存（磁盘持久化，按章稀疏存储，只记 >1 屏的页）。
 *
 * 滚动流的"页码 → 行号"换算依赖每页切片数，而切片数只有图片实际加载后才能测得；
 * 不缓存的话每次进度恢复都按 1 屏估算，全分屏长图章节会系统性定位偏早，
 * 且远端页永不组合、无法靠现场解析自愈。缓存后第二次进入即可按真实行号定位。
 *
 * 键为 `作品id#章号`。本缓存可重建：改键格式后旧条目只是不命中，
 * 相当于重新测一次切片数，不产生错误数据，故不做迁移。
 */
object WebtoonSliceCache {

    private const val PREFS = "jm_webtoon_slices"
    private const val MAX_CHAPTERS = 512

    private val map = java.util.concurrent.ConcurrentHashMap<String, Map<Int, Int>>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private fun sp(context: Context): android.content.SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }

    private fun key(ref: String, order: Int) = "$ref#$order"

    fun load(ref: String, order: Int, context: Context): Map<Int, Int> {
        val key = key(ref, order)
        map[key]?.let { return it }
        val raw = runCatching { sp(context).getString(key, null) }.getOrNull() ?: return emptyMap()
        val parsed = raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val page = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val n = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (page < 0 || n < 2) null else page to n
        }.toMap()
        map[key] = parsed
        return parsed
    }

    fun putAll(context: Context, ref: String, order: Int, counts: Map<Int, Int>) {
        if (counts.isEmpty()) return
        val key = key(ref, order)
        val merged = load(ref, order, context) + counts.filterValues { it > 1 }
        map[key] = merged
        runCatching {
            sp(context).edit()
                .putString(key, merged.entries.joinToString(",") { "${it.key}:${it.value}" })
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
}
