package com.jmread.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 阅读状态：READ=打开过阅读器（已读），FINISHED=读到最后一章最后一页（已读完，优先级高于已读） */
enum class ReadStatus { READ, FINISHED }

/**
 * 已读/已读完状态的内存缓存：
 * 启动时一次性从 DataStore 全量加载（存量进度自动补齐"已读"），阅读器写入时同步更新。
 * 列表页每条 O(1) 内存查询，不碰磁盘；version 递增触发 Compose 列表刷新角标。
 *
 * 键为作品标识（单源 == 漫画 id）。
 */
object ReaderStatus {

    // 并发容器：启动预热(IO 线程 clear+putAll)、阅读器写入(IO)、主线程读取三路并发访问，
    // 普通 HashMap 无同步会在冷启动窗口丢失条目甚至破坏内部结构
    private val map = java.util.concurrent.ConcurrentHashMap<String, ReadStatus>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** App 启动时调用：一次性加载全部进度/读完标记（含历史数据）。挂起函数，在 IO 线程 await 即可 */
    suspend fun loadAll(context: Context) {
        runCatching {
            map.clear()
            map.putAll(ReaderPrefs.current().loadAllStatuses())
            _version.value++
        }
    }

    fun of(ref: String): ReadStatus? = map[ref]

    /** 打开阅读器即已读；状态只升不降（读完的作品不会被降级为仅已读） */
    fun markRead(ref: String) {
        if (map.containsKey(ref)) return
        map[ref] = ReadStatus.READ
        _version.value++
    }

    fun markFinished(ref: String) {
        if (map[ref] == ReadStatus.FINISHED) return
        map[ref] = ReadStatus.FINISHED
        _version.value++
    }
}
