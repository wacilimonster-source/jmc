package com.jmread.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    /**
     * 单调自增唯一 ID。
     * 不能拿 timestamp 当唯一键：同一毫秒内同 tag/同 level 的两条日志（并发请求同时失败时常见）
     * 会生成相同键，LazyColumn 遇重复 key 直接抛异常闪退——而日志页正是排障入口。
     */
    val id: Long,
    val timestamp: Long,
    val tag: String,
    val level: String,
    val message: String,
)

object LogStore {

    private const val MAX_ENTRIES = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 与 logs 同锁递增，保证并发写日志时 ID 不重复 */
    private var nextId = 0L

    @Synchronized
    fun log(tag: String, level: String, message: String) {
        val entry = LogEntry(nextId++, System.currentTimeMillis(), tag, level, message)
        val current = _logs.value
        val updated = if (current.size >= MAX_ENTRIES) {
            current.drop(1) + entry
        } else {
            current + entry
        }
        _logs.value = updated
    }

    @Synchronized
    fun clear() {
        _logs.value = emptyList()
    }

    @Synchronized
    fun allText(): String {
        val sb = StringBuilder()
        for (e in _logs.value) {
            sb.appendLine("[${timeFmt.format(Date(e.timestamp))}] [${e.tag}] [${e.level}] ${e.message}")
        }
        return sb.toString().trimEnd()
    }
}
