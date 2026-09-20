package com.jmread.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用级后台协程作用域（与进程同生命周期）。
 *
 * 用于启动预热、缓存回填等"无人订阅、无人等待"的后台任务；
 * ViewModel / 页面局部任务请使用 viewModelScope 或 rememberCoroutineScope。
 */
object AppScope {
    val IO: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(block: suspend CoroutineScope.() -> Unit) = IO.launch(block = block)
}
