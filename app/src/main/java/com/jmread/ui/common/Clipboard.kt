package com.jmread.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 复制到剪贴板（对标 PiKA v1.12.0「所见即所得」复制设计）。
 *
 * 返回 Snackbar 反馈文案（「已复制作品名」/「已复制作者名」），由调用方展示；
 * 不做任何解析/补全，复制值就是页面展示的完整值。
 */
fun copyToClipboard(context: Context, label: String, text: String): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    return "已复制$label"
}
