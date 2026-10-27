package com.jmread

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jmread.ui.MainScreen
import com.jmread.ui.theme.PiKATextTheme
import com.jmread.ui.theme.PiKATheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private companion object {
        /** 同一进程只包一次默认异常处理器：Activity 重建（旋转/深色切换）不应层层叠加 */
        @Volatile private var crashLoggerInstalled = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        enableEdgeToEdge()
        setContent {
            PiKATheme {
                PiKATextTheme {
                    MainScreen()
                }
            }
        }
    }

    /** 全局未捕获异常：写 crash.log（保留最近 5 次），便于离线定位闪退 */
    private fun installCrashLogger() {
        if (crashLoggerInstalled) return
        crashLoggerInstalled = true
        val dir = File(filesDir, "crash").apply { mkdirs() }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 文件名带毫秒：秒级时间戳下同一秒内的两次崩溃会互相覆盖，丢样本
                val ts = SimpleDateFormat("MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
                val file = File(dir, "crash_$ts.log")
                file.writeText(throwable.stackTraceToString())
                // 只裁剪本处理器自己的 crash_*.log：目录里还有 CrashReporter 的
                // last_crash.txt / startup_diagnostic.txt / freeze.log 等取证文件，
                // 按 lastModified 全量 drop 会把上次会话的 ANR 证据一起删掉。
                dir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("crash_") && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e("CrashLogger", "write crash log failed", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}