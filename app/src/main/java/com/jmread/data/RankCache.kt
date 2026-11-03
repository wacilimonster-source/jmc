package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.RankTab
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 排行榜持久化缓存（P1-07）。
 *
 * 每个榜型一份上次成功结果 + 时间戳；服务端 5xx/超时时降级展示，
 * 而不是整块错误页。缓存身份含源（单源固定）、榜型、schema 版本；
 * 列表/总数/时间戳在同一次 commit 里写入，避免读到半份数据。
 */
object RankCache {

    private const val SCHEMA_VERSION = 1
    private const val PREFS_NAME = "jm_rank_cache"
    private const val MAX_ENTRIES = 120
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    data class CachedRank(
        val items: List<ComicSummary>,
        val total: Int,
        val at: Long,
    )

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun key(type: String) = "v$SCHEMA_VERSION:$type"

    fun load(type: String): CachedRank? {
        val p = prefs ?: return null
        val k = key(type)
        val at = p.getLong("at_$k", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > MAX_AGE_MS) return null
        val items = p.getString("list_$k", null)
            ?.let { runCatching { json.decodeFromString<List<ComicSummary>>(it) }.getOrNull() }
            ?: return null
        // 空列表也是「上次成功结果」：成功空榜必须覆盖旧缓存，
        // 不能让用户误以为旧榜单仍然有效（PERF-05）。
        return CachedRank(items = items, total = p.getInt("total_$k", 0), at = at)
    }

    fun save(type: String, items: List<ComicSummary>, total: Int) {
        val p = prefs ?: return
        val k = key(type)
        // 空成功结果也要覆盖旧缓存（否则已下架的榜单会永远展示旧内容）
        p.edit()
            .putString("list_$k", json.encodeToString(items.take(MAX_ENTRIES)))
            .putInt("total_$k", total)
            .putLong("at_$k", System.currentTimeMillis())
            .apply()
    }

    fun loadTabs(): List<RankTab>? {
        val p = prefs ?: return null
        val at = p.getLong("tabs_at", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > MAX_AGE_MS) return null
        val values = p.getString("tabs", null)
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: return null
        val tabs = values.mapNotNull { v -> RankTab.entries.firstOrNull { it.value == v } }
        return tabs.takeIf { it.isNotEmpty() }
    }

    fun saveTabs(tabs: List<RankTab>) {
        val p = prefs ?: return
        p.edit()
            .putString("tabs", json.encodeToString(tabs.map { it.value }))
            .putLong("tabs_at", System.currentTimeMillis())
            .apply()
    }
}
