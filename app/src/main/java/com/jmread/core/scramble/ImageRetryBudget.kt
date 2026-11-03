package com.jmread.core.scramble

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单个「图片逻辑请求」的重试预算（PERF-03）。
 *
 * 旧实现的分层重试会相乘：HTTP 拦截器最多初始+4、CDN 换线、管线初始+3、
 * 合流失败后重新竞选，最坏可放大到几十次实际传输。这里把「实际传输次数」
 * 与「总 deadline」收敛为一个共享预算：
 *  - 每次真正发起的 HTTP 请求消耗 1 次；
 *  - 换 host、管线解码失败后的 fresh fetch 都从同一预算扣；
 *  - 超过 deadline 立即停止，由调用方给出可重试错误；
 *  - 取消不消耗额外预算，也不触发换线。
 *
 * 时间源可注入，便于 JVM 单测。
 */
class ImageRetryBudget(
    val maxTransports: Int,
    deadlineMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val consumed = AtomicInteger(0)
    private val deadlineAt = nowMs() + deadlineMs.coerceAtLeast(0L)

    fun tryConsumeTransport(): Boolean {
        if (expired()) return false
        while (true) {
            val current = consumed.get()
            if (current >= maxTransports) return false
            if (consumed.compareAndSet(current, current + 1)) return true
        }
    }

    fun remainingTransports(): Int = (maxTransports - consumed.get()).coerceAtLeast(0)

    fun usedTransports(): Int = consumed.get()

    fun remainingMs(): Long = (deadlineAt - nowMs()).coerceAtLeast(0L)

    fun expired(): Boolean = nowMs() >= deadlineAt
}

/** 图片 HTTP 失败：保留状态码与 Retry-After，供统一重试策略判断。 */
internal class ImageHttpException(
    val code: Int,
    val retryAfterMs: Long?,
    message: String,
) : IOException(message)

/**
 * 逻辑请求 → 共享预算的登记表。
 *
 * 以「规范内容 key」为身份（见 [jmImageCacheKey]），因此同一张图的可见请求、
 * 预取和管线重试共用一份预算；手动重试（#retry）会主动清空以获得一次新预算。
 */
object ImageRetryLedger {

    /** 单逻辑请求最多 4 次实际传输 */
    const val MAX_TRANSPORTS = 4

    /** 单逻辑请求总 deadline */
    const val DEADLINE_MS = 20_000L

    /** 失败条目保留时长：覆盖管线重试窗口，之后自然过期 */
    private const val ENTRY_TTL_MS = 60_000L

    private data class Entry(val budget: ImageRetryBudget, val createdAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun budgetFor(key: String): ImageRetryBudget =
        budgetFor(key, System.nanoTime() / 1_000_000L)

    /** 可注入单调时钟的版本（单测用）。 */
    internal fun budgetFor(key: String, nowMs: Long): ImageRetryBudget {
        prune(nowMs)
        val entry = entries.compute(key) { _, existing ->
            // 条目年龄即逻辑请求的 deadline：到点后换一份新预算，
            // 避免 20 秒前的失败耗尽当前请求的重试名额
            if (existing != null && nowMs - existing.createdAtMs < DEADLINE_MS) {
                existing
            } else {
                Entry(ImageRetryBudget(MAX_TRANSPORTS, DEADLINE_MS), nowMs)
            }
        }!!
        return entry.budget
    }

    /** 已有预算（不创建）；管线重试用它判断是否还值得再试一次。 */
    fun existingBudget(key: String): ImageRetryBudget? = entries[key]?.budget

    /** 成功/手动重试时清空：下一次同 key 请求获得全新预算。 */
    fun clear(key: String) {
        entries.remove(key)
    }

    /** 仅供单测复位 */
    fun resetForTest() {
        entries.clear()
    }

    private fun prune(now: Long) {
        if (entries.isEmpty()) return
        entries.entries.removeIf { (_, entry) ->
            now - entry.createdAtMs > ENTRY_TTL_MS
        }
    }
}
