package com.jmread.core.scramble

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 乱序判定结果与占比统计。
 *
 * 判定结果按（photoId/文件名）缓存，同章重进零开销；
 * 计数器是「规则再变」的可观测信号——服务端哪天停止打乱，scrambled 计数占比会掉到 0。
 */
object ScrambleCache {

    private const val MAX_ENTRIES = 4000

    private lateinit var sp: SharedPreferences
    private val verdicts = HashMap<String, Boolean>()
    @Volatile private var totalChecked = 0L
    @Volatile private var totalScrambled = 0L
    private var dirty = false

    fun init(context: Context) {
        sp = context.getSharedPreferences("jm_scramble", Context.MODE_PRIVATE)
        runCatching {
            sp.getString("stats", null)?.let {
                val o = JSONObject(it)
                totalChecked = o.optLong("checked", 0)
                totalScrambled = o.optLong("scrambled", 0)
            }
            sp.getString("map", null)?.let { raw ->
                val o = JSONObject(raw)
                for (k in o.keys()) verdicts[k] = o.getBoolean(k)
            }
        }
    }

    @Volatile private var pendingPuts: MutableMap<String, Boolean> = HashMap()

    fun put(key: String, scrambled: Boolean) {
        val flush: Boolean
        synchronized(this) {
            verdicts[key] = scrambled
            pendingPuts[key] = scrambled
            totalChecked++
            if (scrambled) totalScrambled++
            dirty = true
            // 简单容量控制：超限时丢最旧的一半（HashMap 无序，可接受——缓存可重建）
            flush = verdicts.size > MAX_ENTRIES
            if (flush) {
                val it = verdicts.entries.iterator()
                var removed = 0
                while (it.hasNext() && removed < MAX_ENTRIES / 2) {
                    it.next(); it.remove(); removed++
                }
            }
        }
        if (flush) scheduleFlush(force = true) else scheduleFlush()
    }

    /** 已知判定结果（避免重复判定）；未知返回 null */
    fun get(key: String): Boolean? = synchronized(this) { verdicts[key] }

    /** 乱序页占比（用于日志页展示与规则漂移监控） */
    fun scrambledRatio(): Double =
        if (totalChecked == 0L) 0.0 else totalScrambled.toDouble() / totalChecked

    fun statsText(): String {
        val r = scrambledRatio()
        return "已判定 $totalChecked 页，其中乱序 $totalScrambled 页（${"%.1f".format(r * 100)}%）"
    }

    private fun scheduleFlush(force: Boolean = false) {
        synchronized(this) {
            if (!dirty) return
            if (force) { doFlushLocked(); return }
            if (pendingPuts.size >= 20) doFlushLocked()
        }
    }

    private fun doFlushLocked() {
        if (!dirty) return
        val stats = JSONObject().put("checked", totalChecked).put("scrambled", totalScrambled).toString()
        val map = JSONObject()
        for ((k, v) in verdicts) map.put(k, v)
        sp.edit().putString("stats", stats).putString("map", map.toString()).apply()
        pendingPuts.clear()
        dirty = false
    }
}
