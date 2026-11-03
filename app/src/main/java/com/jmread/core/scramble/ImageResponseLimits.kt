package com.jmread.core.scramble

import kotlinx.coroutines.ensureActive
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * 图片响应体读取的统一约束（PERF-01 / PERF-02）。
 *
 * 旧实现直接 `body.bytes()` / `input.readBytes()`：既没有大小上限（异常大响应
 * 会先撑爆堆再报错），也无法在读取中途响应协程取消。这里分块读取，
 * 每块检查取消，并在超过上限时明确失败。
 */
internal object ImageResponseLimits {
    /**
     * 单张图片原始响应上限。固定数据集（2160×8192 长图）压缩后远小于此值；
     * 超过只可能是异常/风控响应，明确失败比 OOM 可诊断。
     */
    const val MAX_BYTES: Long = 48L * 1024 * 1024
}

/** 分块读取并限制总字节数；每块之间响应协程取消。 */
internal suspend fun InputStream.readBytesCapped(
    maxBytes: Long = ImageResponseLimits.MAX_BYTES,
): ByteArray {
    val out = ByteArrayOutputStream(minOf(maxBytes, 256L * 1024).toInt())
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        coroutineContext.ensureActive()
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            throw IOException("图片响应超过上限（>${maxBytes / (1024 * 1024)}MB），已中止")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** OkHttp 响应体分块读取（调用方负责在 IO 线程执行）。 */
internal suspend fun Response.readBodyBytesCapped(
    maxBytes: Long = ImageResponseLimits.MAX_BYTES,
): ByteArray {
    val body = body ?: throw IOException("图片响应为空")
    return body.byteStream().use { it.readBytesCapped(maxBytes) }
}

/** 解析 Retry-After（秒数或 HTTP 日期），返回毫秒；无法解析返回 null。 */
internal fun parseRetryAfterMs(value: String?): Long? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        return (seconds * 1000L).coerceAtMost(60_000L)
    }
    return runCatching {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        val at = fmt.parse(raw)?.time ?: return@runCatching null
        (at - System.currentTimeMillis()).coerceIn(0L, 60_000L)
    }.getOrNull()
}
