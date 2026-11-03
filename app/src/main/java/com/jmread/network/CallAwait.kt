package com.jmread.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 可取消的 OkHttp 调用（PERF-02）。
 *
 * `Call.execute()` 是同步阻塞的：协程取消后传输仍会继续到返回，连接和带宽被
 * 白占到超时。这里改用 `enqueue()` + [suspendCancellableCoroutine]，并把
 * 取消绑定到**整个调用生命周期**（含响应体读取），而不是只绑定到响应头返回：
 *
 *  - 协程取消 → `call.cancel()`；
 *  - 回调与取消竞态时，已收到的 Response 必须关闭；
 *  - 调用方在 `block` 内读 body，`withCall` 返回前始终保持绑定。
 */
suspend fun <T> OkHttpClient.withCall(
    request: Request,
    block: suspend (Response) -> T,
): T {
    val call = newCall(request)
    val job = coroutineContext[Job]
    // 响应头之后 body 读取阶段仍要能取消：绑定到 Job 完成（仅取消时断开）
    val cancelHandle = job?.invokeOnCompletion { cause ->
        if (cause is CancellationException) call.cancel()
    }
    try {
        val response = call.awaitResponse()
        return response.use { block(it) }
    } finally {
        cancelHandle?.dispose()
    }
}

/** 等待响应头；取消时立即 cancel 并关闭竞态中到达的响应。 */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) {
                response.close()
                return
            }
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
