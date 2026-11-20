package com.jmread.mock

import com.jmread.core.log.LogStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * API 模拟拦截器（debug 专用，挂在 JmClient 请求链最外层）。
 *
 * 职责：
 *  1. MockState.enabled=false → 直接放行真实网络；
 *  2. 故障注入（断网/慢网/5xx/截断）——对标 PiKA「网络相关改动必须做故障注入」的硬规则；
 *  3. 按 URL path 路由到 fixture 模板，短路在签名/TLS/换域之前（换域逻辑用 ERROR500 单测验证）。
 *
 * 线程模型：JmClient/图片下载均走 enqueue + 挂起等待，拦截器运行在 OkHttp 调度线程，
 * Thread.sleep 阻塞的是该次调用的调度线程（debug 专用，可接受；SLOW 档 3s）。
 */
class MockApiInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!MockState.enabled) return chain.proceed(request)

        val path = request.url.encodedPath
        val isPost = request.method == "POST"
        val form = if (isPost) {
            request.body?.let { body ->
                val buf = Buffer()
                runCatching { body.writeTo(buf) }
                MockResponses.parseQuery(buf.readUtf8())
            }.orEmpty()
        } else emptyMap()

        // 1) 故障注入
        when (MockState.fault) {
            MockFault.OFFLINE -> throw IOException("模拟故障注入：断网（jm-mock）")
            MockFault.SLOW -> Thread.sleep(3_000)
            MockFault.ERROR500 -> return respond(request, 500, """{"errorMsg":"模拟 5xx（jm-mock)"}""")
            MockFault.TRUNCATE -> {
                val served = MockResponses.serve(path, MockResponses.parseQuery(request.url.encodedQuery), isPost, form)
                    ?: return chain.proceed(request)
                return respond(request, 200, served.take(120))
            }
            MockFault.NONE -> Unit
        }

        // 2) fixture 路由
        val served = MockResponses.serve(path, MockResponses.parseQuery(request.url.encodedQuery), isPost, form)
        if (served == null) {
            LogStore.log("jm-mock", "W", "未识别的 API path（放行真实网络）：$path")
            return chain.proceed(request)
        }
        return respond(request, 200, served)
    }

    private fun respond(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("jm-mock")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
