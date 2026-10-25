package com.jmread.core.checkin

import android.content.Context
import com.jmread.core.JmRepository
import com.jmread.core.log.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 每日自动签到（0.1.7 起）：登录状态下每次进程启动 / 进入「我的」页时尝试一次，
 * 当日成功即落盘日期、之后不再重复；服务端日历显示已签同样记为完成。
 * 失败不落盘——下次启动或用户点击重试（手动签到按钮与月历卡已移除，签到纯后台化）。
 */
object DailyCheckIn {

    sealed interface State {
        /** 未登录 / 本会话尚未尝试 */
        data object Idle : State
        data object Running : State
        /** 今日自动签到成功，msg 为到账文本（如 "Jcoin:40 EXP:40"） */
        data class Done(val msg: String) : State
        /** 服务端日历显示今天已签过（曾手动签 / 换机等） */
        data object Already : State
        data class Failed(val reason: String) : State
    }

    private const val PREFS = "jm_checkin_prefs"
    private const val KEY_LAST_SUCCESS = "last_success_date"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null

    /** 今日（yyyy-MM-dd）已成功签到的记录；空 = 本日还没成功过 */
    @Volatile private var lastSuccessDate: String = ""

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Application.onCreate 调用：读入当日成功记录 */
    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            lastSuccessDate = appContext!!
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SUCCESS, null).orEmpty()
        }
    }

    /** 是否该发起今日签到（纯函数便于单测）：登录中且当日还没成功过 */
    internal fun due(loggedIn: Boolean, lastSuccessDate: String, today: String): Boolean =
        loggedIn && lastSuccessDate != today

    /** 幂等的自动签到入口：未登录 / 当日已成功 / 正在进行中直接返回 */
    fun maybeCheckIn() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (!due(JmRepository.isLoggedIn, lastSuccessDate, today)) return
        if (!running.compareAndSet(false, true)) return
        _state.value = State.Running
        scope.launch {
            runCatching { JmRepository.checkIn() }.fold(
                onSuccess = { msg ->
                    lastSuccessDate = today
                    persist(today)
                    _state.value = if (msg == "今日已签到") State.Already else State.Done(msg)
                    LogStore.log("checkin", "I", "自动签到完成：$msg")
                },
                onFailure = { e ->
                    _state.value = State.Failed(e.message ?: "签到失败")
                    LogStore.log("checkin", "W", "自动签到失败：${e.message?.take(120)}")
                },
            )
            running.set(false)
        }
    }

    /** 退出登录清内存态与当日记录：换账号同日重登仍为其自动签（服务端会拦重复签到） */
    fun reset() {
        _state.value = State.Idle
        lastSuccessDate = ""
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST_SUCCESS).apply()
        }
    }

    private fun persist(date: String) {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_SUCCESS, date).apply()
        }
    }
}
