package com.jmread.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 作品更新时间记录缓存：任何接口只要拉到 updatedAt 就记下来（详情页是主要来源，
 * 哔咔列表接口不返回该字段）。关注流等列表展示时对没有时间的条目回填已记录的值。
 */
object UpdatedAtCache {
    private const val PREFS_NAME = "updated_at_cache"
    private const val KEY_MAP = "map_json"
    private const val MAX_ENTRIES = 2000

    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null

    /** LinkedHashMap 保持插入序 = 记录新鲜度，超出上限淘汰最旧 */
    private val map = LinkedHashMap<String, String>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistJob: Job? = null
    private var versionJob: Job? = null

    /** 磁盘数据是否已加载完成（init 在后台协程反序列化，完成前 put 先入 pendingPuts） */
    @Volatile private var ready = false
    private val pendingPuts = LinkedHashMap<String, String>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 冷启动主线程不再同步读盘 + 反序列化最多 2000 条（低端机可达数十至上百毫秒），
        // 改为后台加载；期间 put() 的写入先暂存 pendingPuts，加载完成后合并（内存新值优先）
        scope.launch {
            synchronized(map) {
                runCatching {
                    val str = prefs?.getString(KEY_MAP, null)
                    if (str != null) {
                        map.clear()
                        json.decodeFromString<Map<String, String>>(str).forEach { (k, v) -> map[k] = v }
                    }
                }
                synchronized(pendingPuts) {
                    pendingPuts.forEach { (k, v) ->
                        map.remove(k)
                        map[k] = v
                    }
                    pendingPuts.clear()
                }
                ready = true
            }
        }
    }

    fun of(ref: String): String? = synchronized(map) { map[ref] }

    /** 拉到更新时间就记录（同值重复记录不触发 version，避免列表无谓刷新）。[ref] 为作品标识 `源_id` */
    fun put(ref: String, updatedAt: String) {
        if (updatedAt.isBlank()) return
        val needsDebounce: Boolean
        synchronized(map) {
            if (map[ref] == updatedAt) {
                needsDebounce = false
            } else if (!ready) {
                synchronized(pendingPuts) { pendingPuts[ref] = updatedAt }
                needsDebounce = true
            } else {
                map.remove(ref)
                map[ref] = updatedAt
                while (map.size > MAX_ENTRIES) {
                    map.remove(map.keys.first())
                }
                needsDebounce = true
            }
        }
        if (!needsDebounce) return
        // debounce：批量回填时合并为一次落盘 / 一次 version 通知
        versionJob?.cancel()
        versionJob = scope.launch {
            delay(300)
            _version.value++
        }
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            persist()
        }
    }

    private fun persist() {
        val p = prefs ?: return
        val snapshot = synchronized(map) { json.encodeToString(map.toMap()) }
        p.edit().putString(KEY_MAP, snapshot).apply()
    }
}
