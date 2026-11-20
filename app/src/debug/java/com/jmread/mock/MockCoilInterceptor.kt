package com.jmread.mock

import android.util.Base64
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult

/**
 * Coil 管线模拟拦截器（debug 专用，装配在 JmApp 图片管线的最外层）。
 *
 * JmImageFetcher 的模拟挂载点只接管禁漫 /media 图片；头像、外链图等其它 URL
 * 在模拟模式下落回默认网络 Fetcher。本拦截器把 mock.jm.invalid 域名的请求
 * （fixture 里 /static/ 头像路径已改写到该域名）改写为 data: URI 占位图，
 * 由 Coil 内置的 data-URI 解码器处理 —— 不触碰具体版本的 Result 构造 API。
 */
class MockCoilInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (!MockState.enabled) return chain.proceed(request)
        val url = when (val data = request.data) {
            is String -> data
            is android.net.Uri -> data.toString()
            else -> null
        } ?: return chain.proceed(request)
        if (!url.startsWith(MockTemplates.MOCK_HOST)) return chain.proceed(request)

        val bytes = MockImages.generic(url)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val replaced: ImageRequest = request.newBuilder().data("data:image/png;base64,$b64").build()
        return chain.proceed(replaced)
    }
}
