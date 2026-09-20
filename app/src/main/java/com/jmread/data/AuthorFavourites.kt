package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 收藏的作者条目：作者名 + 最近一部作品的封面 */
@Serializable
data class AuthorEntry(
    val author: String,
    val coverUrl: String = "",
    /**
     * 收藏时间，用于列表稳定排序。
     * 必须为可空 —— 若用 `= System.currentTimeMillis()` 作默认值，
     * 磁盘上缺失该字段的旧条目每次反序列化都会生成新时间戳，
     * 导致 get() 的排序结果每次读取都不同（关注来源顺序抖动）。
     */
    val createdAt: Long? = null,
)

/**
 * 作者收藏持久化（本地）：SharedPreferences 存 JSON。
 * 哔咔服务端无关注作者接口，纯本地功能。
 */
object AuthorFavourites {
    private const val PREFS_NAME = "author_favourites"
    private const val KEY_LIST = "author_list"

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 全部收藏作者（按收藏时间倒序；无时间戳的旧条目沉到最后，保证顺序稳定） */
    fun get(): List<AuthorEntry> {
        val p = prefs ?: return emptyList()
        val str = p.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AuthorEntry>>(str) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt ?: 0L }
    }

    fun contains(author: String): Boolean = get().any { it.author == author }

    /** 收藏（已存在则刷新封面与时间并置顶） */
    fun add(author: String, coverUrl: String = "") {
        if (author.isBlank()) return
        val list = get().filterNot { it.author == author }.toMutableList()
        list.add(0, AuthorEntry(author = author, coverUrl = coverUrl, createdAt = System.currentTimeMillis()))
        save(list)
    }

    fun remove(author: String) {
        save(get().filterNot { it.author == author })
    }

    private fun save(list: List<AuthorEntry>) {
        val p = prefs ?: return
        p.edit().putString(KEY_LIST, json.encodeToString(list)).apply()
    }
}
