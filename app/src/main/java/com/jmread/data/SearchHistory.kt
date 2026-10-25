package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 搜索历史（10 条，去重置顶，搜索页内可清空）。
 *
 * 与 GridSettings 同一范式：init 后台预热内存缓存，写操作只更新内存 + StateFlow
 * 后投递后台落盘。存 SharedPreferences + JSON 数组（词表极小，不值得 DataStore）；
 * 关键词可能含任意字符，不用分隔符拼接而用 JSON 转义。
 */
object SearchHistory {

    const val MAX_ENTRIES = 10

    private const val PREFS = "jm_search_history"
    private const val KEY = "keywords"

    private val _keywords = MutableStateFlow<List<String>>(emptyList())

    /** 最近搜索词（新→旧），供搜索页 chips 订阅 */
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private lateinit var appContext: Context
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        ioScope.launch {
            _keywords.value = runCatching { load() }.getOrDefault(emptyList())
        }
    }

    private fun sp(): SharedPreferences? {
        if (!::appContext.isInitialized) return null
        return prefs ?: appContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
    }

    private fun load(): List<String> {
        val raw = sp()?.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<String>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            sp()?.edit()?.putString(KEY, arr.toString())?.apply()
        }
    }

    /** 记录一次搜索（空白词忽略；去重后置顶，超上限丢最旧） */
    fun record(keyword: String) {
        val next = sanitize(_keywords.value, keyword)
        if (next == _keywords.value) return
        _keywords.value = next
        ioScope.launch { persist(next) }
    }

    /** 清空全部（搜索页「清空」入口） */
    fun clear() {
        _keywords.value = emptyList()
        ioScope.launch { runCatching { sp()?.edit()?.remove(KEY)?.apply() } }
    }

    /**
     * 纯函数（单测口径）：trim 后空白忽略；已有项去重并提到最前；截断到 [MAX_ENTRIES]。
     */
    fun sanitize(current: List<String>, newKeyword: String): List<String> {
        val kw = newKeyword.trim()
        if (kw.isEmpty()) return current
        return (listOf(kw) + current.filter { it != kw }).take(MAX_ENTRIES)
    }
}
