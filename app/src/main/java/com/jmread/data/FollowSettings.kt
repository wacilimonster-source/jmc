package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一个关键词关注项：一组关键词（组内为"且"关系）+ 可选标签（官方词表，null = 不限制） */
@Serializable
data class FollowItem(
    val keywords: List<String>,
    val tag: String? = null,
    /**
     * 添加时间，用于列表稳定排序。
     * 必须为可空 —— 若用 `= System.currentTimeMillis()` 作默认值，
     * 磁盘上缺失该字段的旧条目每次反序列化都会生成新时间戳，
     * 导致 items() 的排序结果每次读取都不同（关注来源顺序抖动）。
     */
    val createdAt: Long? = null,
)

/**
 * 首页"个人关注"设置：关键词关注（组合关键词，且关系）。
 * 本地 SharedPreferences 存 JSON（哔咔服务端无关注接口，纯本地）。
 */
object FollowSettings {
    private const val PREFS_NAME = "follow_settings"
    private const val KEY_ITEMS = "follow_items"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 关注项（按添加时间倒序；无时间戳的旧条目沉到最后，保证顺序稳定） */
    fun items(): List<FollowItem> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FollowItem>>(str) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt ?: 0L }
    }

    fun contains(keywords: List<String>, tag: String? = null): Boolean =
        items().any { it.keywords == keywords && it.tag == tag }

    fun addItem(keywords: List<String>, tag: String? = null) {
        val words = keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.isEmpty()) return
        val list = items().filterNot { it.keywords == words && it.tag == tag }.toMutableList()
        var ts = System.currentTimeMillis()
        while (list.any { it.createdAt == ts }) ts++
        list.add(0, FollowItem(keywords = words, tag = tag, createdAt = ts))
        val p = prefs ?: return
        p.edit().putString(KEY_ITEMS, json.encodeToString(list)).commit()
    }

    /**
     * 删除关注项（按内容匹配 keywords+tag，不依赖 createdAt：
     * createdAt 可空，旧数据可能缺失该字段，按时间戳删不掉）。
     * addItem 保证同内容只存一条，按内容删除是安全的。
     */
    fun removeItem(item: FollowItem) {
        val p = prefs ?: return
        p.edit()
            .putString(
                KEY_ITEMS,
                json.encodeToString(
                    items().filterNot { it.keywords == item.keywords && it.tag == item.tag }
                ),
            )
            .commit()
    }
}