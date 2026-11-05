package com.jmread.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 兼容包装：系列 Typography 已在 [PiKATheme] 全量应用（Type.kt），
 * 这里不再新建 TextStyle 覆盖（I13：override 必须用 copy 保留字号/行高），
 * 仅保留调用点兼容并透传主题。
 */
@Composable
fun PiKATextTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = MaterialTheme.typography, content = content)
}
