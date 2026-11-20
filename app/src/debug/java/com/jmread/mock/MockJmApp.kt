package com.jmread.mock

import com.jmread.JmApp
import com.jmread.core.debug.DebugHooks

/**
 * debug 变体的 Application：在 JmApp 初始化之前装配模拟层钩子，
 * 确保首个网络请求/图片加载发生前拦截器已就位。
 * 通过 app/src/debug/AndroidManifest.xml 覆盖 application android:name 接管。
 */
class MockJmApp : JmApp() {

    override fun onCreate() {
        // 钩子先于一切网络初始化装配（JmApp.onCreate 会在末尾触发 /setting 刷新）
        MockResponses.init(this)
        MockState.init(this)
        DebugHooks.apiInterceptor = MockApiInterceptor()
        DebugHooks.imageBytes = { url -> MockImages.provide(url) }
        DebugHooks.coilInterceptor = MockCoilInterceptor()
        DebugHooks.deeplinkHandler = { uri -> MockState.handleDeeplink(uri) }
        super.onCreate()
    }
}
