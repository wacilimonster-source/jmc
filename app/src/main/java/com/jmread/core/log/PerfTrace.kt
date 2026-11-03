package com.jmread.core.log

import android.os.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有限、可配对的性能 trace 入口（PERF-00）。
 *
 * - [section] 用于**同一线程**内的同步 CPU 工作（条带搬运、编码、合并）；
 * - [beginAsync] 用于可能跨线程/挂起的阶段（网络、排队、pages-ready），
 *   用 cookie 配对，绝不能把同步 section 跨线程闭合；
 * - [Trace.isEnabled] 为 false 时全部退化为直接执行，不产生额外分配。
 *
 * 统一使用 `app.init.*` / `repo.album` / `reader.pagesReady` / `image.*` /
 * `download.reconcile` / `library.merge` 等固定名称，方便 Perfetto 过滤与对比。
 */
object PerfTrace {

    class Token internal constructor(val name: String, val cookie: Int) {
        private val ended = AtomicBoolean(false)

        fun end() {
            if (ended.compareAndSet(false, true)) {
                runCatching { Trace.endAsyncSection(name, cookie) }
            }
        }
    }

    private val nextCookie = AtomicInteger(1)

    inline fun <T> section(name: String, block: () -> T): T {
        if (!Trace.isEnabled()) return block()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** 开始异步阶段；未开启 trace 时返回 null，[endAsync] 对 null 安全。 */
    fun beginAsync(name: String): Token? {
        if (!Trace.isEnabled()) return null
        val cookie = nextCookie.getAndIncrement()
        Trace.beginAsyncSection(name, cookie)
        return Token(name, cookie)
    }

    fun endAsync(token: Token?) {
        token?.end()
    }

    /** 便捷写法：整个 suspend 阶段（可能跨线程）包成一个异步 section。 */
    suspend fun <T> asyncSection(name: String, block: suspend () -> T): T {
        val token = beginAsync(name)
        try {
            return block()
        } finally {
            endAsync(token)
        }
    }
}
