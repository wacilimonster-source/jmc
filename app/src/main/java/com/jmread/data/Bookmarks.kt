package com.jmread.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jmread.core.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.bookmarksDataStore by preferencesDataStore(name = "jm_bookmarks")

/**
 * 本地书架（本地收藏）：不依赖账号的主收藏形态。
 *
 * 产品依据：本 App 免登录可用，云端收藏写入参数未验证（能力位 false），
 * 详情页心形操作本地书架；云端收藏态（is_favorite）仅作只读回填展示。
 */
object Bookmarks {

    @kotlinx.serialization.Serializable
    data class BookmarkItem(
        val comicId: String,
        val title: String,
        val author: String = "",
        val coverUrl: String = "",
        val addedAt: Long,
        /** 记录时的最新章节/更新信息（可选，用于书架展示） */
        val note: String = "",
    )

    private val KEY = stringPreferencesKey("items_json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow<List<BookmarkItem>>(emptyList())
    val items: StateFlow<List<BookmarkItem>> = _items.asStateFlow()

    private lateinit var context: Context

    fun init(appContext: Context) {
        context = appContext.applicationContext
        AppScope.launch { load() }
    }

    private suspend fun load() {
        runCatching {
            val raw = context.bookmarksDataStore.data.first()[KEY]
            val list = if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<BookmarkItem>>(raw) }.getOrDefault(emptyList())
            _items.value = list
        }
    }

    fun contains(comicId: String): Boolean = _items.value.any { it.comicId == comicId }

    fun get(comicId: String): BookmarkItem? = _items.value.firstOrNull { it.comicId == comicId }

    fun add(item: BookmarkItem) {
        val updated = listOf(item) + _items.value.filterNot { it.comicId == item.comicId }
        persist(updated)
    }

    fun remove(comicId: String) {
        persist(_items.value.filterNot { it.comicId == comicId })
    }

    /** 切换收藏态，返回切换后的状态 */
    fun toggle(comicId: String, title: String, author: String, coverUrl: String, note: String = ""): Boolean {
        return if (contains(comicId)) {
            remove(comicId)
            false
        } else {
            add(BookmarkItem(comicId, title, author, coverUrl, System.currentTimeMillis(), note))
            true
        }
    }

    private fun persist(list: List<BookmarkItem>) {
        _items.value = list
        val ctx = context
        AppScope.launch {
            runCatching {
                ctx.bookmarksDataStore.edit { it[KEY] = json.encodeToString(list) }
            }
        }
    }
}
