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

    /**
     * 必须由 JmApp.onCreate 调用。
     *
     * 用可空而不是 lateinit：本地书架是未登录用户点详情心形时的高频入口，
     * 若装配漏掉 init，lateinit 会直接让点击闪退（0.1.8 真机复测事故）。
     * 守卫后至少退化为「仅内存、不落盘」，不会崩。
     */
    private var context: Context? = null

    /** 读改写串行化：书架/详情可能同时收藏不同作品，不能各自基于旧快照覆盖。 */
    private val lock = Any()
    /** 落盘版本号：异步写只有最新版本才真正执行，防止旧快照后落盘。 */
    private var writeVersion = 0L

    fun init(appContext: Context) {
        context = appContext.applicationContext
        AppScope.launch { load() }
    }

    private suspend fun load() {
        val ctx = context ?: return
        runCatching {
            val raw = ctx.bookmarksDataStore.data.first()[KEY]
            val list = if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<BookmarkItem>>(raw) }.getOrDefault(emptyList())
            synchronized(lock) {
                // 预热期间用户已操作的条目以内存为准，旧磁盘数据只补缺
                _items.value = if (writeVersion == 0L) {
                    list
                } else {
                    (_items.value + list).distinctBy { it.comicId }
                }
            }
        }
    }

    fun contains(comicId: String): Boolean =
        synchronized(lock) { _items.value.any { it.comicId == comicId } }

    fun get(comicId: String): BookmarkItem? =
        synchronized(lock) { _items.value.firstOrNull { it.comicId == comicId } }

    fun add(item: BookmarkItem) {
        synchronized(lock) {
            persistLocked(listOf(item) + _items.value.filterNot { it.comicId == item.comicId })
        }
    }

    fun remove(comicId: String) {
        synchronized(lock) {
            persistLocked(_items.value.filterNot { it.comicId == comicId })
        }
    }

    /** 切换收藏态，返回切换后的状态 */
    fun toggle(comicId: String, title: String, author: String, coverUrl: String, note: String = ""): Boolean =
        synchronized(lock) {
            if (_items.value.any { it.comicId == comicId }) {
                persistLocked(_items.value.filterNot { it.comicId == comicId })
                false
            } else {
                persistLocked(
                    listOf(
                        BookmarkItem(comicId, title, author, coverUrl, System.currentTimeMillis(), note),
                    ) + _items.value,
                )
                true
            }
        }

    /** 调用方必须持有 [lock]。 */
    private fun persistLocked(list: List<BookmarkItem>) {
        _items.value = list
        val ctx = context ?: return
        val version = ++writeVersion
        AppScope.launch {
            // 更新版本已排队：旧快照直接放弃，避免后写覆盖前写
            synchronized(lock) { if (version != writeVersion) return@launch }
            runCatching {
                ctx.bookmarksDataStore.edit { it[KEY] = json.encodeToString(list) }
            }
        }
    }
}
