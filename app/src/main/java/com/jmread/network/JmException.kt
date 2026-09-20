package com.jmread.network

import java.io.IOException

/**
 * 禁漫接口异常，携带 HTTP 状态码供结构化判断。
 *
 * 会话失效判断走 [httpCode] == 401，不做中文文案匹配
 * （PiKA 的 D9：JmException 不带 http 码导致凭据失效静默）。
 */
class JmException(
    message: String,
    val httpCode: Int = 0,
) : IOException(message) {

    /** 凭据被服务端拒绝（会话失效 / 无权限） */
    val isCredentialRejected: Boolean get() = httpCode == 401 || httpCode == 403

    /** 服务端整体不可用（镜像集体故障 / 风控），可用于触发「换线路」UI */
    val isServerError: Boolean get() = httpCode >= 500 || httpCode == 0
}
