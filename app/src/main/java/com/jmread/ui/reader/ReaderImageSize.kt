package com.jmread.ui.reader

import android.content.Context
import coil.size.Size
import com.jmread.core.MemoryBudget

/**
 * 阅读器解码尺寸的唯一来源（P0-03）。
 *
 * - 翻页模式：按视口尺寸请求，再按共享宽高上限等比封顶；不放大小图。
 * - 滚动流：宽度按屏宽，高度用内存分档上限（整页高度由切片布局消费）。
 * - 预取必须复用同一函数，保证缓存键与展示请求的解码尺寸一致。
 */
internal const val MAX_DECODE_WIDTH_PX = 2160

internal fun maxDecodeHeightPx(context: Context): Int =
    MemoryBudget.maxDecodeHeightPx(context)

internal fun pagerDecodeSize(context: Context): Size {
    val dm = context.resources.displayMetrics
    val width = dm.widthPixels.coerceAtLeast(1).coerceAtMost(MAX_DECODE_WIDTH_PX)
    val height = dm.heightPixels.coerceAtLeast(1).coerceAtMost(maxDecodeHeightPx(context))
    return Size(width, height)
}

internal fun webtoonDecodeSize(context: Context): Size {
    val dm = context.resources.displayMetrics
    val width = dm.widthPixels.coerceAtLeast(1).coerceAtMost(MAX_DECODE_WIDTH_PX)
    return Size(width, maxDecodeHeightPx(context))
}
