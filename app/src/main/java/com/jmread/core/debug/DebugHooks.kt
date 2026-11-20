package com.jmread.core.debug

import coil.intercept.Interceptor
import okhttp3.Interceptor as OkInterceptor
import android.net.Uri

/**
 * 调试/测试钩子集中点。
 *
 * 设计约束（对标 PiKA「构建成功 ≠ 可运行」与故障注入实践）：
 *  - release 变体里所有钩子恒为 null，零行为、零开销；模拟层只存在于 debug 变体
 *    （app/src/debug），通过 Manifest 覆盖 Application（MockJmApp）在启动时装配。
 *  - main 代码只提供「挂载点」，不 import 任何 debug 类，也不做反射查找。
 *  - 复测一律走模拟数据：fixture（app/src/test/resources/jm，与单测同源）+ 本地生成图片，
 *    故障注入（断网/慢网/5xx/响应截断）用于验证错误路径，不依赖真实账号与真实内容。
 */
object DebugHooks {

    /** API 层模拟拦截器：短路在签名/TLS 之前，按 URL path 返回 fixture（仅 debug 设置） */
    @Volatile var apiInterceptor: OkInterceptor? = null

    /** 图片字节模拟：返回 null 表示不接管（仅 debug 设置） */
    @Volatile var imageBytes: (suspend (url: String) -> ByteArray?)? = null

    /** Coil 管线模拟拦截器：头像等非禁漫 /media 路径的占位图（仅 debug 设置） */
    @Volatile var coilInterceptor: Interceptor? = null

    /**
     * 调试深链处理器：MainActivity 收到 VIEW intent 时先交给它；
     * 返回 true 表示已消费（jmread://mock?...）。release 恒为 null。
     */
    @Volatile var deeplinkHandler: ((Uri) -> Boolean)? = null
}
