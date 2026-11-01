package com.jmread.ui.common

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.SnackbarHostState

/**
 * 应用级 Snackbar 宿主（P1-08）。
 *
 * 挂在 MainScreen 的 Scaffold 上，跨路由共享：在书架清除阅读记录后
 * 即使返回首页/详情，撤销提示仍可见，期限不被页面销毁重置。
 */
object AppSnackbar {
    val hostState = SnackbarHostState()

    /** 撤销期限：默认 10s；系统无障碍建议更长时取建议值（P1-08） */
    @Volatile
    var undoTimeoutMs: Long = 10_000L
        private set

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= 29) {
            val am = context.getSystemService(AccessibilityManager::class.java)
            val recommended = am?.getRecommendedTimeoutMillis(
                10_000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS,
            )
            if (recommended != null) {
                undoTimeoutMs = recommended.coerceAtLeast(10_000).toLong()
            }
        }
    }
}
