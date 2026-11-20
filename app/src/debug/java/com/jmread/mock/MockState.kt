package com.jmread.mock

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.jmread.core.log.LogStore

/** 可注入的故障形态：验证错误路径用（对标 PiKA 截断代理/限流注入实践） */
enum class MockFault(val label: String) {
    NONE("正常"),
    OFFLINE("断网"),
    SLOW("慢网+3s"),
    ERROR500("服务端5xx"),
    TRUNCATE("响应截断");

    companion object {
        /** 深链参数别名：error/500 → ERROR500，slow/timeout → SLOW，offline/断网 → OFFLINE，truncate/截断 → TRUNCATE */
        private val ALIASES = mapOf(
            "error" to ERROR500, "500" to ERROR500, "5xx" to ERROR500, "server" to ERROR500,
            "slow" to SLOW, "timeout" to SLOW, "慢网" to SLOW,
            "offline" to OFFLINE, "断网" to OFFLINE, "net" to OFFLINE,
            "truncate" to TRUNCATE, "截断" to TRUNCATE, "cut" to TRUNCATE,
            "none" to NONE, "off" to NONE, "正常" to NONE,
        )

        fun of(raw: String?): MockFault {
            if (raw.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.name.equals(raw, true) }
                ?: ALIASES[raw.lowercase()]
                ?: entries.firstOrNull { it.label == raw }
                ?: NONE
        }
    }
}

/**
 * 模拟层运行状态（仅 debug 变体存在）。
 *
 * 开关与故障形态持久化在 SP（jm_mock），通过调试深链切换：
 *   jmread://mock            → 查看状态
 *   jmread://mock?off=1      → 关闭模拟（走真实网络）
 *   jmread://mock?on=1       → 开启模拟（默认开）
 *   jmread://mock?fault=offline|slow|error|truncate|none
 */
object MockState {

    @Volatile var enabled: Boolean = true
        private set
    @Volatile var fault: MockFault = MockFault.NONE
        private set

    private const val SP = "jm_mock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FAULT = "fault"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        enabled = sp.getBoolean(KEY_ENABLED, true)
        fault = MockFault.of(sp.getString(KEY_FAULT, MockFault.NONE.name))
        LogStore.log(
            "jm-mock", "I",
            "模拟数据层已装配：enabled=$enabled fault=${fault.label}（fixture 与单测同源）",
        )
    }

    private fun persist() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_FAULT, fault.name)
            .apply()
    }

    private fun toast(msg: String) {
        val ctx = appContext ?: return
        Toast.makeText(ctx, "[模拟] $msg", Toast.LENGTH_SHORT).show()
    }

    /** @return true 表示该深链已由模拟层消费 */
    fun handleDeeplink(uri: Uri): Boolean {
        if (uri.host != "mock") return false
        val params = uri.queryParameters
        when {
            params["on"] == "1" -> {
                enabled = true
                toast("模拟数据已开启，fault=${fault.label}")
            }
            params["off"] == "1" -> {
                enabled = false
                toast("模拟数据已关闭（走真实网络）")
            }
            params.containsKey("fault") -> {
                fault = MockFault.of(params["fault"])
                toast("故障注入：${fault.label}")
            }
            else -> toast("enabled=$enabled fault=${fault.label}")
        }
        LogStore.log("jm-mock", "I", "deep link：enabled=$enabled fault=${fault.label}")
        persist()
        return true
    }
}

private val Uri.queryParameters: Map<String, String>
    get() = buildMap {
        queryParameterNames.forEach { name -> getQueryParameter(name)?.let { put(name, it) } }
    }
