package com.jmread.core

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.jmread.core.scramble.ImageProcessingBudget

/**
 * 进程级内存预算：统一低内存档位、预取开关与图片缓存清理时机。
 *
 * 设计口径（P0-03 / P2-07 / P2-08）：
 * - 单图解码上限只是“单张不爆 Canvas”，不等于总内存安全；
 * - 低内存设备或系统进入 RUNNING_LOW 后，预取降为 0，只保留当前页；
 * - UI 隐藏/后台/严重压力时释放可回收图片缓存，回到前台按需重载。
 */
object MemoryBudget {

    private const val PRESSURE_TTL_MS = 60_000L

    @Volatile private var initialized = false
    @Volatile private var lowRamDevice = false
    @Volatile private var memoryClassMb = 192
    @Volatile private var lastPressureAt = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        lowRamDevice = am?.isLowRamDevice == true
        memoryClassMb = am?.memoryClass ?: 192
        // PERF-01：图片还原/压缩的总字节预算按设备档位注入（低内存只允许一张进入还原）
        ImageProcessingBudget.configureForDevice(lowRamDevice, memoryClassMb)
        // PERF-02：图片传输配额同档位收敛（低内存全局 2 路、预取 0）
        com.jmread.core.scramble.ImageWorkScheduler.configure(lowRamDevice || memoryClassMb <= 128)
    }

    /** 设备本身低内存（isLowRamDevice 或 memoryClass ≤ 128MB） */
    val isLowRam: Boolean
        get() = lowRamDevice || memoryClassMb <= 128

    /** 最近一分钟内是否收到过 RUNNING_LOW/CRITICAL 等压力回调 */
    fun isUnderPressure(now: Long = System.currentTimeMillis()): Boolean =
        lastPressureAt > 0 && now - lastPressureAt < PRESSURE_TTL_MS

    /** 是否关闭预取：低内存设备或近期压力回调 */
    fun shouldDisablePrefetch(): Boolean = isLowRam || isUnderPressure()

    /** 翻页模式预取页数：正常 1，低内存/压力 0 */
    fun pagerPrefetchPages(): Int = if (shouldDisablePrefetch()) 0 else 1

    /**
     * 处理系统内存回调。
     * @return true = 可以释放图片内存缓存（UI 隐藏/后台/严重压力）
     */
    fun onTrimMemory(level: Int): Boolean {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                lastPressureAt = System.currentTimeMillis()
                return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            }

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                lastPressureAt = System.currentTimeMillis()
                return true
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // 轻度压力：仅缩短压力窗口，不立刻清缓存
                return false
            }
        }
        return false
    }

    /** 解码高度上限（px）：与 WebtoonSplitPage 的既有分档保持一致。 */
    fun maxDecodeHeightPx(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memClass = am?.memoryClass ?: 192
        return when {
            memClass >= 256 -> 8192
            memClass >= 128 -> 6144
            else -> 4096
        }
    }
}
