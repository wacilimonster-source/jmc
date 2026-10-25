package com.jmread.core.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局更新状态：App 启动时自动检查一次（update.json），
 * 有新版时供首页横幅 / 我的角标消费。
 *
 * 免打扰：首页横幅点「取消」即对该版本静默（snoozed_version 落盘），
 * 之后每次启动的自动检查不再打扰；手动检查（设置页）不受影响，永远如实展示。
 * 新版本号发布后（> 已静默版本）自动恢复提醒。
 */
object UpdateState {

    private const val PREFS = "jm_update_prefs"
    private const val KEY_SNOOZED = "snoozed_version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: android.content.Context? = null

    /** 已被用户「稍后」的版本（内存缓存，init 时从 SP 读入） */
    @Volatile private var snoozedVersion: String = ""

    /** 在 Application.onCreate 调用：读入免打扰记录 */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        runCatching {
            snoozedVersion = appContext!!
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(KEY_SNOOZED, null).orEmpty()
        }
    }

    private val _updateInfo = MutableStateFlow<UpdateManager.UpdateInfo?>(null)

    /** null=无更新或未检查；非 null=有新版本 */
    val updateInfo: StateFlow<UpdateManager.UpdateInfo?> = _updateInfo.asStateFlow()

    /** 最近一次手动检查的失败原因（用于向用户如实反馈"检查失败"而非"已是最新"） */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 检查一次（幂等：已有结果不重复弹；被免打扰的版本不再打扰） */
    fun checkOnce() {
        scope.launch {
            if (_updateInfo.value != null) return@launch
            val result = runCatching { UpdateManager.checkResult() }.getOrNull() ?: return@launch
            when (result) {
                is UpdateManager.CheckResult.Available -> {
                    // 与免打扰版本相同或更旧：跳过（新版本号 > 已静默版本时才会再提醒）
                    if (snoozedVersion.isNotBlank() &&
                        !UpdateManager.isNewer(result.info.version, snoozedVersion)
                    ) {
                        com.jmread.core.log.LogStore.log(
                            "UpdateState", "I", "v${result.info.version} 已被免打扰（静默于 v$snoozedVersion），跳过自动提醒",
                        )
                        return@launch
                    }
                    _updateInfo.value = result.info
                    _lastError.value = null
                }
                UpdateManager.CheckResult.UpToDate -> {
                    _lastError.value = null
                }
                is UpdateManager.CheckResult.Failed -> {
                    // 保留原因：判为"无更新"会让用户误以为已是最新
                    _lastError.value = result.reason
                }
            }
        }
    }

    /** 手动检查（无论结果都更新状态，并透传失败原因；手动检查不受免打扰约束） */
    fun checkManual() {
        scope.launch {
            when (val result = runCatching { UpdateManager.checkResult() }.getOrNull()) {
                is UpdateManager.CheckResult.Available -> {
                    _updateInfo.value = result.info
                    _lastError.value = null
                }
                UpdateManager.CheckResult.UpToDate -> {
                    _updateInfo.value = null
                    _lastError.value = null
                }
                is UpdateManager.CheckResult.Failed -> {
                    _updateInfo.value = null
                    _lastError.value = result.reason
                }
                null -> {
                    _updateInfo.value = null
                    _lastError.value = "网络异常或服务器未就绪"
                }
            }
        }
    }

    /** 关闭当前更新提醒（首页横幅「取消」）——对该版本静默至下一个新版本 */
    fun dismiss() {
        val version = _updateInfo.value?.version
        _updateInfo.value = null
        if (version.isNullOrBlank()) return
        snoozedVersion = version
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_SNOOZED, version).apply()
        }
    }
}
