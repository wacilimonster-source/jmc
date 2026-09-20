package com.jmread.network

import android.util.Base64
import com.jmread.core.netconfig.DomainPool
import com.jmread.data.SourcePrefs
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 禁漫（jmcomic）移动端 API 的签名与解密工具。
 *
 * 实测结论（2026-09-19，pika/reports/jm-source-design-20260919.html）：
 *  - 每个请求必须带签名头：token=md5(ts+APP_SECRET)，tokenparam="{ts},{版本号}"
 *  - 响应体的 data 字段默认是 base64 + AES-256-ECB 密文，key=md5(ts+APP_SECRET)，去 PKCS7
 *  - /setting 与 /chapter_view_template 的 data 是明文，需做类型判断后跳过解密
 *  - 版本号取 /setting.jm3_version 动态携带（写死旧版本号是风控隐患）
 *  - 图片/封面走独立 CDN，host 由 DomainPool 管理（内置表 + /setting.img_host 刷新）
 */
object JmCrypto {

    /** 请求签名密钥（APP_TOKEN_SECRET / APP_DATA_SECRET 同源） */
    private const val APP_SECRET = "185Hcomic3PAPP7R"

    /** 客户端版本号兜底值（线上 jm3_version=2.1.8；运行时被 /setting 刷新覆盖） */
    const val FALLBACK_VERSION = "2.1.8"

    val appVersion: String
        get() = SourcePrefs.current().jmVersion ?: FALLBACK_VERSION

    data class Sign(
        val ts: Long,
        val token: String,
        val tokenparam: String,
    )

    /** 按时间戳生成请求签名 */
    fun sign(): Sign {
        val ts = System.currentTimeMillis() / 1000
        return Sign(ts, md5("$ts$APP_SECRET"), "$ts,$appVersion")
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** base64 -> AES-256-ECB -> 去 PKCS7 padding */
    fun decrypt(data: String, ts: Long): String {
        val raw = Base64.decode(data, Base64.DEFAULT)
        val key = md5("$ts$APP_SECRET").toByteArray()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = cipher.doFinal(raw)
        val pad = out[out.lastIndex].toInt() and 0xFF
        if (pad !in 1..16 || pad > out.size) {
            throw IllegalStateException("解密失败：padding 非法 (pad=$pad, size=${out.size})，密钥或时间戳可能不匹配")
        }
        return String(out, 0, out.size - pad, Charsets.UTF_8)
    }

    /** 章节图片直链 */
    fun imageUrl(photoId: String, filename: String): String =
        "https://${DomainPool.imageHostFor(photoId)}/media/photos/$photoId/$filename"

    /** 封面直链（列表/详情通用） */
    fun coverUrl(albumId: String): String =
        "https://${DomainPool.imageHostFor(albumId)}/media/albums/$albumId.jpg"

    /** 高清封面（3:4，列表卡片优先用，失败由调用方回退普通封面） */
    fun coverUrlHd(albumId: String): String =
        "https://${DomainPool.imageHostFor(albumId)}/media/albums/${albumId}_3x4.jpg"
}
