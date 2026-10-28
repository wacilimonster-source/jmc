package com.jmread.core.scramble

import android.util.Log
import com.jmread.core.log.CrashReporter
import com.jmread.core.log.LogStore
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Semaphore
import kotlin.random.Random

/**
 * 图片 HTTP 层瞬时失败自动重试。
 *
 * 漫画图片失败大多是瞬时的：连接重置、读写超时、CDN 边缘 5xx/限流。此前这类失败
 * 只能由用户手点重试；本拦截器在同一次 Coil 请求内静默多试几次，成功后 painter
 * 直接从 Loading 到 Success，不触发 Compose 重组/重建请求。
 *
 * 只重试瞬时失败：IOException 与 403/408/429/5xx（含 Cloudflare 52x）。
 * 404 等确定性失败不重试。取消（滚出屏幕/切章）立即放行，绝不产生僵尸重试。
 *
 * 自动重试名额独立限制，避免弱网恢复瞬间几十页同时重试把正常请求挤死。
 */
class ImageRetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = chain.call()
        var attempt = 0
        var slotHeld = false
        try {
            while (true) {
                try {
                    val response = chain.proceed(request)
                    val retryable = response.code.isTransientHttpFailure() &&
                        attempt < MAX_RETRIES &&
                        !call.isCanceled()
                    if (!retryable) {
                        if (slotHeld && attempt >= MAX_RETRIES && !response.isSuccessful) {
                            logGiveUp("HTTP ${response.code}", call)
                        }
                        return response
                    }
                    if (!slotHeld && !retrySlots.tryAcquire()) {
                        val msg =
                            "图片自动重试并发已满($MAX_CONCURRENT_RETRIES)，本次按失败交还（可手动重试）：" +
                                "HTTP ${response.code} ${shortUrl(call)}"
                        LogStore.log(TAG, "W", msg)
                        CrashReporter.appendImageFailure(msg)
                        return response
                    }
                    slotHeld = true
                    val code = response.code
                    // 释放失败响应占用的连接后再重试
                    response.close()
                    logRetry(attempt, "HTTP $code", call)
                } catch (e: IOException) {
                    if (call.isCanceled() || attempt >= MAX_RETRIES) {
                        if (slotHeld && attempt >= MAX_RETRIES && !call.isCanceled()) {
                            logGiveUp(e.javaClass.simpleName, call)
                        }
                        throw e
                    }
                    if (!slotHeld && !retrySlots.tryAcquire()) throw e
                    slotHeld = true
                    logRetry(attempt, e.javaClass.simpleName, call)
                }
                backoff(attempt)
                attempt++
            }
        } finally {
            if (slotHeld) retrySlots.release()
        }
    }

    private fun backoff(attempt: Int) {
        try {
            Thread.sleep(BACKOFF_MS[attempt] + Random.nextLong(0, 400))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Retry backoff interrupted")
        }
    }

    private fun logRetry(attempt: Int, cause: String, call: Call) {
        val msg = "图片瞬时失败($cause)，${BACKOFF_MS[attempt]}ms 后第 ${attempt + 1} 次自动重试：" +
            shortUrl(call)
        Log.i(TAG, msg)
        LogStore.log(TAG, "I", msg)
    }

    private fun logGiveUp(cause: String, call: Call) {
        val msg = "图片自动重试${MAX_RETRIES}次后仍失败($cause)，等待手动重试：${shortUrl(call)}"
        Log.w(TAG, msg)
        LogStore.log(TAG, "W", msg)
        CrashReporter.appendImageFailure(msg)
    }

    private fun shortUrl(call: Call): String =
        call.request().url.toString().let { if (it.length > 140) "…${it.takeLast(140)}" else it }

    private fun Int.isTransientHttpFailure(): Boolean =
        this == 403 || this == 408 || this == 429 || this in 500..599

    private companion object {
        const val TAG = "JmImgRetry"
        const val MAX_RETRIES = 4
        val BACKOFF_MS = longArrayOf(400L, 1_200L, 3_000L, 6_000L)

        /** 重试名额必须小于 OkHttp 每主机并发上限，保证正常请求仍有槽位 */
        const val MAX_CONCURRENT_RETRIES = 6
        val retrySlots = Semaphore(MAX_CONCURRENT_RETRIES)
    }
}
