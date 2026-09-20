package com.jmread.network

import android.util.Log
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.Security
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 用 BouncyCastle 的纯 Java TLS 栈替换 Android 默认的 Conscrypt(BoringSSL)。
 *
 * 根因（已在本机用同一代理/出口 IP 实测验证）：
 *   - Android 默认 Conscrypt / Chromium-Cronet 都是 BoringSSL，
 *     Cloudflare Bot Management 对其 ClientHello 指纹判定为 bot → 直接返回 1023。
 *   - OpenSSL(curl/Python)、SunJSSE、BouncyCastle 等非 BoringSSL 指纹均可正常通过（实测 200）。
 *
 * 因此只需把 TLS 实现换成 BouncyCastle（纯 DEX，打包进 APK，无需 NDK/外部进程）。
 *
 * 注意：TrustManager 使用平台默认 TrustManagerFactory（系统 CA 校验链），
 * BCJSSE 只负责替换 TLS 协议栈实现（绕开 BoringSSL 指纹），证书校验保持完整。
 */
object BcTls {
    var sslSocketFactory: javax.net.ssl.SSLSocketFactory? = null
        private set
    private var trustManager: X509TrustManager? = null
    private var installed = false

    /** 最近一次安装失败原因（供设置页网络诊断展示；null = 未失败） */
    @Volatile
    var lastError: String? = null
        private set

    /** 每请求路径只告警一次，避免降级后刷屏 */
    @Volatile
    private var warnedFallback = false

    @Synchronized
    fun install() {
        if (installed) return
        try {
            Security.addProvider(BouncyCastleProvider())
            Security.addProvider(BouncyCastleJsseProvider())
            // 用系统默认 CA 信任链做证书校验，避免"信任所有证书"
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            val tms = tmf.trustManagers
            val ctx = SSLContext.getInstance("TLS", "BCJSSE")
            ctx.init(null, tms, SecureRandom())
            sslSocketFactory = ctx.socketFactory
            trustManager = tms.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: throw IllegalStateException("系统 TrustManagerFactory 未提供 X509TrustManager")
            installed = true
            lastError = null
            Log.i("BcTls", "BouncyCastle TLS 已安装（系统 CA 校验）— 绕过 Cloudflare BoringSSL 拦截")
        } catch (e: Throwable) {
            // 失败必须落到 LogStore：BCJSSE 是绕开 Cloudflare 的**唯一依赖**，
            // 装配失败会让整个哔咔源全量失败，而症状看起来只是"网络/限流"，
            // 仅写 android.util.Log 会让排查成本极高。
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            lastError = msg
            Log.e("BcTls", "BouncyCastle TLS 安装失败: $msg")
            com.jmread.core.log.LogStore.log("BcTls", "E", "BouncyCastle TLS 安装失败: $msg")
        }
    }

    /** 确保可用：未安装或曾失败时重试一次（与 JmClient / UpdateManager 的调用方式对齐） */
    fun ensureInstalled(): Boolean {
        if (isAvailable()) return true
        install()
        return isAvailable()
    }

    fun isAvailable(): Boolean = sslSocketFactory != null && trustManager != null

    /** 给 OkHttpClient.Builder 挂上 BC TLS（不可用则回退平台默认并告警） */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val sf = sslSocketFactory
        val tm = trustManager
        if (sf != null && tm != null) {
            builder.sslSocketFactory(sf, tm)
        } else {
            warnFallbackOnce()
        }
        return builder
    }

    /** 降级告警只打一次：降级后每个请求都会走到这里，逐次打印会淹没日志 */
    private fun warnFallbackOnce() {
        if (warnedFallback) return
        warnedFallback = true
        Log.w("BcTls", "BC TLS 不可用，回退平台默认（可能被 1023 拦截）")
        com.jmread.core.log.LogStore.log("BcTls", "W", "BC TLS 不可用，回退平台默认（可能被 Cloudflare 拦截）")
    }

    /** 供 PicaHttpEngine 等每请求路径复用同一份"只告警一次"逻辑 */
    fun warnFallbackShared() = warnFallbackOnce()

    /** 供 Coil 图片加载使用（同样走 BC TLS，避免漫画图片被 Cloudflare 拦截） */
    val imageLoaderClient: OkHttpClient by lazy {
        applyTo(OkHttpClient.Builder()).build()
    }

    /**
     * 为 HttpURLConnection 挂上 BC TLS（下载器用）。
     * 主机名校验保留 HttpsURLConnection 默认行为；BC TLS 不可用时回退平台默认。
     */
    fun openConnection(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        val sf = sslSocketFactory
        if (url.protocol == "https" && sf != null) {
            (conn as HttpsURLConnection).sslSocketFactory = sf
        }
        return conn
    }
}
