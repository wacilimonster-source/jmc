package com.jmread.core.scramble

import android.util.Log
import coil.intercept.Interceptor
import coil.network.HttpException
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import com.jmread.core.log.CrashReporter
import com.jmread.core.log.LogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.coroutines.coroutineContext

/**
 * Coil 管线级自动重试，覆盖 HTTP 响应头之后才发生的失败：
 * 响应体流中途断裂、解码失败等。这些失败对 OkHttp 拦截器不可见，表现却正是
 * 「页面加载失败，手点重试立刻好」。
 *
 * 只重试 IOException 与瞬时 HTTP 错误；404 等确定性失败直接交还。第 2 次起
 * 禁用磁盘缓存读，绕过可能被半截响应污染的条目；成功仍写回。
 */
class CoilRetryInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        var attempt = 0
        var held = false
        try {
            while (true) {
                val result = chain.proceed(requestOf(request, attempt))
                val error = (result as? ErrorResult)?.throwable
                val retryable = error != null && error.isTransient() && attempt < MAX_RETRIES
                if (!retryable) {
                    if (error != null && attempt >= MAX_RETRIES) logGiveUp(error, request)
                    return result
                }
                if (!held && !slots.tryAcquire()) {
                    val msg = "图片管线重试并发已满($MAX_CONCURRENT)，按失败交还（可手动重试）：" +
                        "${error.javaClass.simpleName} ${shortUrl(request)}"
                    Log.w(TAG, msg)
                    LogStore.log(TAG, "W", msg)
                    CrashReporter.appendImageFailure(msg)
                    return result
                }
                held = true
                logRetry(attempt, error, request)
                delay(BACKOFF_MS[attempt])
                attempt++
            }
        } finally {
            if (held) slots.release()
        }
    }

    private fun requestOf(request: ImageRequest, attempt: Int): ImageRequest =
        if (attempt >= FRESH_ATTEMPT) {
            request.newBuilder().diskCachePolicy(CachePolicy.WRITE_ONLY).build()
        } else {
            request
        }

    private fun Throwable.isTransient(): Boolean {
        if (this is IOException) return true
        if (this !is HttpException) return false
        val code = response.code
        return code == 403 || code == 408 || code == 429 || code in 500..599
    }

    private fun logRetry(attempt: Int, error: Throwable, request: ImageRequest) {
        val msg = "图片管线失败(${error.javaClass.simpleName}: ${error.message})，" +
            "${BACKOFF_MS[attempt]}ms 后第 ${attempt + 1} 次重试" +
            (if (attempt == FRESH_ATTEMPT) "（绕过磁盘缓存）" else "") +
            "：${shortUrl(request)}"
        Log.w(TAG, msg)
        LogStore.log(TAG, "W", msg)
    }

    private fun logGiveUp(error: Throwable, request: ImageRequest) {
        val msg = "图片管线重试${MAX_RETRIES}次后仍失败" +
            "(${error.javaClass.simpleName}: ${error.message})，等待手动重试：${shortUrl(request)}"
        Log.w(TAG, msg)
        LogStore.log(TAG, "W", msg)
        CrashReporter.appendImageFailure(msg)
    }

    private fun shortUrl(request: ImageRequest): String =
        request.data.toString().let { if (it.length > 140) "…${it.takeLast(140)}" else it }

    private companion object {
        const val TAG = "JmImgPipeRetry"
        const val MAX_RETRIES = 3
        const val FRESH_ATTEMPT = 2
        val BACKOFF_MS = longArrayOf(500L, 1_500L, 3_000L)
        const val MAX_CONCURRENT = 12
        val slots = Semaphore(MAX_CONCURRENT)
    }
}

/**
 * 在途图片请求合流：相同「缓存键 + 解码尺寸」的并发请求只放行首个，其余等待并共享结果。
 *
 * 阅读器把一张长图切成多个切片，同一页会同时发起最多 6 个请求；不合并会导致同一张
 * 数 MB 原图被并发下载/解码多次，是流量和瞬时内存峰值的最大风险点。
 *
 * 失败结果不共享：首领失败时等待者重新竞选，避免一次网络抖动把整页所有切片一起判死。
 * 首领结束后先摘除在途条目再唤醒等待者，防止 Main.immediate 内联恢复造成主线程活锁。
 */
class CoalescingInterceptor : Interceptor {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageResult>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = coalesceKey(request, chain)
        var reelects = 0
        while (true) {
            val mine = CompletableDeferred<ImageResult>()
            val leader = inFlight.putIfAbsent(key, mine)
            if (leader == null) {
                val outcome = runCatching { chain.proceed(request) }
                // 先摘除自己，再唤醒等待者；顺序相反会造成主线程活锁
                inFlight.remove(key, mine)
                outcome
                    .onSuccess { mine.complete(it) }
                    .onFailure { mine.completeExceptionally(it) }
                return outcome.getOrThrow()
            }

            val shared = try {
                leader.await()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                null
            } catch (e: Exception) {
                null
            }
            if (shared != null) {
                if (shared is ErrorResult && reelects < MAX_REELECT) {
                    reelects++
                } else {
                    // 必须以等待者自己的 request 重建结果，否则 Coil 会报
                    // 「Interceptor cannot modify the request's target」
                    return when (shared) {
                        is SuccessResult -> shared.copy(request = chain.request)
                        is ErrorResult -> shared.copy(request = chain.request)
                    }
                }
            }
            // 循环：竞选新首领或等待新首领
        }
    }

    private fun coalesceKey(request: ImageRequest, chain: Interceptor.Chain): String {
        val cacheKey = request.memoryCacheKey ?: request.diskCacheKey ?: request.data.toString()
        val size = chain.size
        return "$cacheKey|${size.width}x${size.height}"
    }

    private companion object {
        const val MAX_REELECT = 3
    }
}

/**
 * 图片管线终态取证：HTTP 层看不到的响应体中断/解码失败在这里留下最后一条证据。
 */
class ImageErrorLoggingInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed(chain.request)
        if (result is ErrorResult) {
            val t = result.throwable
            val msg = "图片最终失败(${t.javaClass.simpleName}: ${t.message}) <- ${chain.request.data}"
            Log.e(TAG, msg)
            LogStore.log(TAG, "E", msg)
            CrashReporter.appendImageFailure(msg)
        }
        return result
    }

    private companion object {
        const val TAG = "JmImgErr"
    }
}
