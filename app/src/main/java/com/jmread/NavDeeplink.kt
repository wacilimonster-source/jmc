package com.jmread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 通知/外部入口待处理路由。
 *
 * 用 Compose State 而不是普通 var：MainScreen 在组合期读取即可订阅，
 * 导航图就绪后消费；冷启动（onCreate）和温启动（onNewIntent）都写入同一处。
 */
object NavDeeplink {
    var pendingRoute by mutableStateOf<String?>(null)
}
