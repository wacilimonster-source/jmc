package com.jmread.core.model

import com.jmread.core.log.LogStore
import com.jmread.network.JmException
import kotlinx.serialization.SerializationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 用户可见错误文案映射（对标 PiKA v1.12.3-1：错误文案不再泄漏技术细节）。
 *
 * 规则：
 *  - 服务端已给出可读 errorMsg（JmException.serverMsg）→ 原样展示（登录失败「账号或密码错误」等）；
 *  - 断网/超时/DNS → 统一「网络连接失败，请检查网络后重试」；
 *  - DTO 解析失败 → 「服务返回数据异常」，不暴露 JSON/HTML 碎片；
 *  - 其余 → 「服务暂时不可用 / 操作失败」；
 *  - 原始异常文本只写入调试日志（jm-err），供排障取证，不再直接上 UI。
 *
 * 层位说明：放在 core/model 以便纯 JVM 单测（不依赖 Android 类）。
 */
object UserMessages {
    const val NETWORK = "网络连接失败，请检查网络后重试"
    const val SERVER = "服务暂时不可用，请稍后重试"
    const val DATA = "服务返回数据异常，请稍后重试"
    const val AUTH_EXPIRED = "登录已过期，请重新登录"
    const val GENERIC = "操作失败，请稍后重试"
}

/**
 * 把任意异常映射为用户可读文案；原始文本只进调试日志。
 *
 * @param action 动作名（「搜索」「加载排行榜」…），仅用于日志取证，不进文案。
 */
fun Throwable.userMessage(action: String = "加载"): String = when (this) {
    // JmException 继承 IOException，必须先于 IOException 分支判断
    is JmException -> when {
        httpCode == 401 -> serverMsg?.takeIf { it.isNotBlank() } ?: UserMessages.AUTH_EXPIRED
        !serverMsg.isNullOrBlank() -> serverMsg
        else -> UserMessages.SERVER
    }
    is SerializationException, is IllegalArgumentException -> UserMessages.DATA
    is UnknownHostException, is SocketTimeoutException, is ConnectException,
    is SSLException, is EOFException -> UserMessages.NETWORK
    is IOException -> UserMessages.NETWORK
    else -> UserMessages.GENERIC
}.also { friendly ->
    LogStore.log(
        "jm-err", "WARN",
        "${action}失败（展示：$friendly）原始：${message?.take(160) ?: javaClass.simpleName}",
    )
}
