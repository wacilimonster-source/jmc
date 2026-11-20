package com.jmread.core.model

import com.jmread.network.JmException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 错误文案映射矩阵（对标 PiKA v1.12.3-1：用户可见文案不泄漏技术细节）。
 */
class UserMessagesTest {

    private fun msg(t: Throwable) = t.userMessage("测试")

    @Test
    fun `网络类异常统一映射为网络连接失败`() {
        assertEquals(UserMessages.NETWORK, msg(UnknownHostException("Unable to resolve host xxx")))
        assertEquals(UserMessages.NETWORK, msg(SocketTimeoutException("timeout")))
        assertEquals(UserMessages.NETWORK, msg(ConnectException("Failed to connect")))
        assertEquals(UserMessages.NETWORK, msg(SSLException("handshake")))
        assertEquals(UserMessages.NETWORK, msg(EOFException(" broken")))
        assertEquals(UserMessages.NETWORK, msg(IOException("unexpected end of stream")))
    }

    @Test
    fun `DTO 解析失败映射为数据异常且不暴露 JSON 片段`() {
        val e = SerializationException("Unexpected JSON token at offset 120: Expected '\"' but got '}'")
        val shown = msg(e)
        assertEquals(UserMessages.DATA, shown)
        assertTrue("文案不得包含技术细节", !shown.contains("JSON") && !shown.contains("offset"))
    }

    @Test
    fun `JmException 带服务端可读文案时原样展示`() {
        val e = JmException("禁漫接口 403：无权访问", 403, serverMsg = "无权访问")
        assertEquals("无权访问", msg(e))
    }

    @Test
    fun `JmException 401 无服务端文案映射为登录过期`() {
        assertEquals(UserMessages.AUTH_EXPIRED, msg(JmException("禁漫接口 401：x", 401)))
    }

    @Test
    fun `JmException 无结构信息映射为服务暂不可用`() {
        assertEquals(UserMessages.SERVER, msg(JmException("禁漫接口 500（服务端故障，换域重试）", 500)))
        assertEquals(UserMessages.SERVER, msg(JmException("禁漫端点不存在或已下线：Not legal.xxx")))
    }

    @Test
    fun `JmException 是 IOException 子类但优先按结构化分支处理`() {
        // 防回归：when 顺序错了会把 JmException 当普通 IOException 映射成 NETWORK
        val e = JmException("禁漫接口 403: 无权访问", 403, serverMsg = "无权访问")
        assertEquals("无权访问", msg(e))
    }

    @Test
    fun `未知异常映射为操作失败`() {
        assertEquals(UserMessages.GENERIC, msg(IllegalStateException("boom")))
    }

    @Test
    fun `原始异常文本写入调试日志供取证`() {
        val before = com.jmread.core.log.LogStore.logs.value.size
        msg(UnknownHostException("Unable to resolve host secret-host.example"))
        val logs = com.jmread.core.log.LogStore.logs.value
        assertTrue("应新增一条取证日志", logs.size > before)
        assertTrue("日志含原始异常文本", logs.last().message.contains("secret-host.example"))
        assertTrue("日志含动作名", logs.last().message.contains("测试"))
    }
}
