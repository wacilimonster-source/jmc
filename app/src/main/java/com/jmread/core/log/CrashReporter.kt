package com.jmread.core.log

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 前后台状态（ActivityLifecycleCallbacks 维护）：心跳文件据此标注 fg/bg，
 *  用于判断「死前是否在前台」——前台被杀 ≈ ANR/系统终止，后台被杀多为正常回收 */
object AppSessionState {
    @Volatile
    var startedActivities = 0
    val isForeground: Boolean get() = startedActivities > 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}

/**
 * 崩溃/异常退出取证与落盘（移植自 PiKA v1.9.x，去掉图片失败通道）。
 *
 * 背景：LogStore 是纯内存环形缓冲，进程死亡即清零；且「卡住然后闪退」未必是 Java 异常
 * ——ANR（主线程阻塞被系统杀）与系统内存不足杀（lmkd）都**不经过**
 * UncaughtExceptionHandler，这两类退出不留任何痕迹（真机复现：闪退后调试日志为空）。
 *
 * 本对象覆盖三类取证通道：
 * 1. **未捕获异常**：UncaughtExceptionHandler 同步写 last_crash.txt
 *    （堆栈 + 版本 + 设备 + LogStore 尾部 + 系统 crash 缓冲）。
 * 2. **系统退出原因**（API 30+）：启动时读 ApplicationExitInfo——ANR / 内存不足 /
 *    原生崩溃 / 信号终止一览无余，按时间戳去重后写入 startup_diagnostic.txt。
 * 3. **前台心跳 + 冻结栈**（全版本兜底）：看门狗每 4s 写 last_alive.txt（含 fg/bg）；
 *    主线程阻塞 ≥4s 时把主线程栈**立即落盘** freeze.log（内存里的 LogStore 会随
 *    ANR 一起死，必须写文件）。API<30 无退出原因 API，用「前台心跳新鲜且无崩溃文件」
 *    判定非正常退出。
 *
 * 约束：崩溃回调运行在垂死进程里，实现必须完全同步、快、且自身绝不能抛异常。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"
    private const val DIAG_FILE = "startup_diagnostic.txt"
    private const val FREEZE_FILE = "freeze.log"
    private const val ALIVE_FILE = "last_alive.txt"
    private const val EXIT_SEEN_FILE = "last_exit_seen.txt"

    /** 前台心跳判定窗口：死前心跳距下次启动 < 90s 视为「死于运行中」 */
    private const val ALIVE_FRESH_WINDOW_MS = 90_000L

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 最先调用。先汇总上次会话的诊断证据，再装崩溃钩子。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        prepareStartupDiagnostics(context)
        if (pendingReport(context) != null) {
            LogStore.log(TAG, "E", "检测到上次会话异常证据（崩溃/退出原因/冻结记录），设置 → 调试日志 顶部可查看或复制")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(context.applicationContext, thread, throwable)
            } catch (t: Throwable) {
                // 崩溃处理链绝不能再抛
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    // ── 对外查询接口（日志页横幅用） ──────────────────────────────────────

    /** 是否有未捕获异常报告（横幅标题分级用） */
    fun hasCrashReport(context: Context): Boolean =
        file(context, CRASH_FILE).let { it.exists() && it.length() > 0L }

    /** 完整报告全文（崩溃报告 + 启动诊断合并）；无则 null */
    fun pendingReport(context: Context): String? {
        val crash = readText(context, CRASH_FILE)
        val diag = readText(context, DIAG_FILE)
        return when {
            crash != null && diag != null -> crash + "\n\n" + diag
            crash != null -> crash
            diag != null -> diag
            else -> null
        }
    }

    /** 用户点「忽略」后清除全部报告文件 */
    fun dismissReport(context: Context) {
        runCatching {
            file(context, CRASH_FILE).delete()
            file(context, DIAG_FILE).delete()
            file(context, FREEZE_FILE).delete()
        }
    }

    // ── 启动诊断：退出原因 + 心跳 + 冻结记录 ─────────────────────────────

    private fun prepareStartupDiagnostics(context: Context) {
        try {
            val sections = mutableListOf<String>()

            // 1) 系统退出原因（API 30+，权威）
            if (Build.VERSION.SDK_INT >= 30) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val last = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                    ?.maxByOrNull { it.timestamp }
                if (last != null) {
                    val seen = readText(context, EXIT_SEEN_FILE)?.toLongOrNull() ?: -1L
                    if (last.timestamp > seen) {
                        // 无论正常与否都记账，避免旧退出在后续启动被重复上报
                        writeText(context, EXIT_SEEN_FILE, last.timestamp.toString())
                        if (isAbnormalExit(last.reason)) {
                            sections += buildString {
                                append("上次退出原因：")
                                append(exitReasonText(last))
                                append("\n时间: ")
                                append(fmtFull(last.timestamp))
                                append("\n进程: ")
                                append(last.processName)
                                last.description?.let { append("\n系统描述: ").append(it) }
                            }
                        }
                    }
                }
            } else {
                // 2) API 26-29：前台心跳兜底——死前心跳新鲜 + 前台 + 无崩溃文件 ⇒ 非正常退出
                val alive = readText(context, ALIVE_FILE)
                val ts = alive?.substringBefore(' ')?.toLongOrNull()
                val wasForeground = alive?.endsWith("fg") == true
                if (ts != null && wasForeground &&
                    System.currentTimeMillis() - ts < ALIVE_FRESH_WINDOW_MS &&
                    !hasCrashReport(context)
                ) {
                    sections += buildString {
                        append("上次为非正常退出：前台运行中被终止，且无 Java 崩溃报告")
                        append("（API<30 无法读取系统退出原因，疑似 ANR 或系统内存终止）")
                        append("\n最后心跳: ")
                        append(fmtFull(ts))
                    }
                }
            }

            // 3) 上次会话的看门狗冻结记录（主线程阻塞栈，落盘过才能活过 ANR）
            val freeze = readText(context, FREEZE_FILE)
            if (!freeze.isNullOrBlank()) {
                sections += "--- 上次会话主线程冻结记录（≥4s 阻塞，看门狗落盘） ---\n$freeze"
            }
            writeText(context, FREEZE_FILE, "") // 已消费，防跨会话累积

            if (sections.isNotEmpty()) {
                // 带生成时间戳：报告在用户点「忽略」前会一直显示，
                // 无时间戳会把上一轮的旧报告误当成新证据（PiKA 真机已发生过一次）
                writeText(
                    context, DIAG_FILE,
                    "=== 上次会话诊断（生成于 ${fmtFull(System.currentTimeMillis())}） ===\n" +
                        sections.joinToString("\n\n"),
                )
            }
        } catch (t: Throwable) {
            // 诊断失败不致命
        }
    }

    private fun isAbnormalExit(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_SIGNALED ||
            reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
            reason == ApplicationExitInfo.REASON_OTHER

    private fun exitReasonText(info: ApplicationExitInfo): String = when (info.reason) {
        ApplicationExitInfo.REASON_ANR ->
            "应用无响应（ANR）——主线程被长时间阻塞，被系统终止"
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "系统内存不足——进程被系统终止（内存压力杀进程）"
        ApplicationExitInfo.REASON_CRASH ->
            "未捕获的 Java 异常崩溃（应另有崩溃堆栈报告）"
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "原生代码崩溃（native crash）"
        ApplicationExitInfo.REASON_SIGNALED ->
            "被信号终止（SIGSEGV/SIGKILL 等，常见于原生崩溃或系统强杀）"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "资源占用过高（内存/CPU），被系统终止"
        else -> "被系统终止（reason=${info.reason}）"
    }

    // ── 未捕获异常报告 ───────────────────────────────────────────────────

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val sb = StringBuilder()
        sb.appendLine("=== JM 崩溃报告 ===")
        sb.appendLine("时间: ${fmtFull(System.currentTimeMillis())}")
        sb.appendLine("版本: ${versionText(context)}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} · Android API ${Build.VERSION.SDK_INT}")
        sb.appendLine("线程: ${thread.name}${if (thread === Looper.getMainLooper().thread) "（主线程）" else ""}")
        sb.appendLine()
        sb.appendLine("--- 崩溃堆栈 ---")
        sb.appendLine(Log.getStackTraceString(throwable))
        sb.appendLine()
        sb.appendLine("--- 崩溃前应用日志（尾部） ---")
        sb.appendLine(runCatching { LogStore.allText() }.getOrDefault("(不可用)"))
        sb.appendLine()
        sb.appendLine("--- 系统 crash 缓冲（尽力而为，仅本应用可读部分） ---")
        sb.appendLine(logcatCrashBuffer().ifBlank { "(空)" })
        writeText(context, CRASH_FILE, sb.toString())
    }

    /** 读取系统 crash 缓冲最近 300 行（无 READ_LOGS 时仅本应用进程的行，恰好含自身堆栈） */
    private fun logcatCrashBuffer(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-b", "crash", "-t", "300")
            .redirectErrorStream(true)
            .start()
        p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        ""
    }

    // ── 看门狗与心跳的写入接口 ───────────────────────────────────────────

    /** [MainThreadWatchdog] 专用：立即落盘一次冻结记录（LogStore 会随 ANR 死亡，文件不会） */
    fun appendFreezeRecord(context: Context, text: String) {
        runCatching {
            val f = file(context, FREEZE_FILE)
            // 防膨胀：超过 96KB 丢弃旧记录（冻结记录首条最有价值，直接清空重来）
            if (f.length() > 96 * 1024) f.delete()
            f.appendText("${fmtFull(System.currentTimeMillis())}\n$text\n\n")
        }
    }

    /** [MainThreadWatchdog] 专用：覆写前台心跳 */
    fun writeHeartbeat(context: Context) {
        runCatching {
            file(context, ALIVE_FILE).writeText(
                "${System.currentTimeMillis()} ${if (AppSessionState.isForeground) "fg" else "bg"}",
            )
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    internal fun versionText(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    } catch (t: Throwable) {
        "unknown"
    }

    private fun fmtFull(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))

    private fun file(context: Context, name: String): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, name)

    private fun readText(context: Context, name: String): String? {
        val f = file(context, name)
        return if (f.exists() && f.length() > 0L) {
            runCatching { f.readText() }.getOrNull()
        } else null
    }

    private fun writeText(context: Context, name: String, text: String) {
        runCatching { file(context, name).writeText(text) }
    }
}

/**
 * 主线程卡顿看门狗：每 4 秒向主线程投递心跳，未按时到达即判定主线程阻塞：
 * - 心跳文件覆写（含 fg/bg 前后台标记，供非正常退出判定）
 * - 阻塞时记录主线程栈到 LogStore **并立即落盘 freeze 记录**
 *  （内存日志会随 ANR 一起死，落盘才能进入下次启动的诊断报告）
 */
object MainThreadWatchdog {

    @Volatile
    private var started = false
    private var appContext: Context? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "main-thread-watchdog", isDaemon = true) {
            val arrived = AtomicBoolean(false)
            var lastDump = 0L
            while (true) {
                arrived.set(false)
                main.post { arrived.set(true) }
                Thread.sleep(4000)
                val ctx = appContext
                if (ctx != null) CrashReporter.writeHeartbeat(ctx)
                if (!arrived.get()) {
                    val now = System.currentTimeMillis()
                    if (now - lastDump >= 10_000) {
                        lastDump = now
                        val stack = Looper.getMainLooper().thread?.stackTrace
                            ?.joinToString("\n") { "    at $it" }
                            .orEmpty()
                        val text = "主线程阻塞 ≥4s（卡顿/ANR 前兆）。主线程栈：\n$stack"
                        LogStore.log("Watchdog", "E", text)
                        if (ctx != null) CrashReporter.appendFreezeRecord(ctx, text)
                    }
                }
            }
        }
    }
}
