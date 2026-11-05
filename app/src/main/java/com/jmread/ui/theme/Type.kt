package com.jmread.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 系列字阶（design-tokens.json）：
 * 页标题 22/28、区块标题 18/24、详情标题 24/32、正文 14/22、
 * 辅助 12/18、必要小字 11/16；两列卡名 14/20/600，三列 12/18/600。
 * 最多两行 / 一行省略由使用处控制，不整体缩字。
 */
private val pageTitle = TextStyle(
    fontSize = 22.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.SemiBold,
)

private val sectionTitle = TextStyle(
    fontSize = 18.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.SemiBold,
)

private val detailTitle = TextStyle(
    fontSize = 24.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.SemiBold,
)

private val body = TextStyle(
    fontSize = 14.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.Normal,
)

private val secondary = TextStyle(
    fontSize = 12.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Normal,
)

private val auxiliary = TextStyle(
    fontSize = 11.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)

val typography = Typography(
    displayLarge = TextStyle(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    displayMedium = TextStyle(
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    displaySmall = TextStyle(
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineLarge = detailTitle.copy(fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = detailTitle,
    headlineSmall = sectionTitle,
    titleLarge = pageTitle,
    titleMedium = sectionTitle,
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = body.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = body,
    bodySmall = secondary,
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = secondary.copy(fontWeight = FontWeight.Medium),
    labelSmall = auxiliary,
)
