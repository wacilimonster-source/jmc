package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import com.jmread.core.model.ComicSummary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 首页关注流持久化缓存：上次成功刷新的结果，冷启动时立即展示。
 */
object FollowFeedCache {
    private const val PREFS_NAME = "follow_feed_cache"
    private const val KEY_FEED = "feed_json"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): List<ComicSummary> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_FEED, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ComicSummary>>(str) }
            .getOrDefault(emptyList())
    }

    fun save(feed: List<ComicSummary>) {
        val p = prefs ?: return
        p.edit().putString(KEY_FEED, json.encodeToString(feed.take(120))).apply()
    }

    fun clear() {
        val p = prefs ?: return
        p.edit().remove(KEY_FEED).apply()
    }
}
