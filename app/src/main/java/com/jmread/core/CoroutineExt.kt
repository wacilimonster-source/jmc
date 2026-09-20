package com.jmread.core

import kotlinx.coroutines.CancellationException

/**
 * 与 [runCatching] 行为一致，但**放行协程取消**。
 *
 * 为什么需要它：`CancellationException` 是 `Exception` 的子类，标准库的 `runCatching`
 * 更会捕获 `Throwable`，因此 `runCatching { ... }` 会把协程取消当成普通失败吞掉。
 * 后果是结构化并发失效——被取消的协程会在下一个挂起点前继续写状态，
 * 或在 `finally` 里错误复位由**新任务**设置的标志位（典型症状：新搜索的转圈被旧任务关掉）。
 *
 * 协程内的「一把梭」捕获请一律使用本函数替代 `runCatching`；
 * 手写 try/catch 时，也请把 `catch (e: CancellationException) { throw e }` 放在
 * `catch (e: Exception)` **之前**。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
