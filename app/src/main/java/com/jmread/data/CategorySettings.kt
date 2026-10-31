package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 分类显示设置持久化：排序顺序 + 隐藏列表。
 * 使用 SharedPreferences 存储 JSON。
 */
object CategorySettings {
    private const val PREFS_NAME = "category_settings"
    private const val KEY_ORDER = "category_order"
    private const val KEY_HIDDEN = "hidden_categories"
    private const val KEY_PINNED_TAGS = "pinned_tags"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Settings(
        val order: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
        /** 用户置顶的官方标签；保序，最多 12 个 */
        val pinnedTags: List<String> = emptyList(),
    )

    const val MAX_PINNED_TAGS = 12

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun get(): Settings {
        val p = prefs ?: return Settings()
        val orderStr = p.getString(KEY_ORDER, null)
        val hiddenStr = p.getString(KEY_HIDDEN, null)
        return Settings(
            order = if (orderStr != null) runCatching { json.decodeFromString<List<String>>(orderStr) }.getOrDefault(emptyList()) else emptyList(),
            hidden = if (hiddenStr != null) runCatching { json.decodeFromString<Set<String>>(hiddenStr) }.getOrDefault(emptySet()) else emptySet(),
            pinnedTags = p.getString(KEY_PINNED_TAGS, null)?.let {
                runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
            } ?: emptyList(),
        )
    }

    fun save(settings: Settings) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_ORDER, json.encodeToString(settings.order))
            .putString(KEY_HIDDEN, json.encodeToString(settings.hidden))
            .putString(KEY_PINNED_TAGS, json.encodeToString(settings.pinnedTags.distinct().take(MAX_PINNED_TAGS)))
            .apply()
    }

    /** 切换置顶标签；超过上限时保留较早置顶的标签 */
    fun togglePinnedTag(current: Settings, tag: String): Settings {
        val trimmed = tag.trim()
        if (trimmed.isBlank()) return current
        val next = if (trimmed in current.pinnedTags) {
            current.pinnedTags - trimmed
        } else {
            (current.pinnedTags + trimmed).take(MAX_PINNED_TAGS)
        }
        return current.copy(pinnedTags = next)
    }
}
