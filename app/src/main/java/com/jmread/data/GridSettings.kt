package com.jmread.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.uiDataStore by preferencesDataStore(name = "pika_ui")

/**
 * 浏览界面偏好：作品网格每行数量（2/3，默认 2）。
 *
 * 与 ReaderPrefs 同一范式：init 后台预热内存缓存，写操作只更新内存 + StateFlow
 * 后投递后台落盘，绝不在调用线程同步等待。网格页 collectAsState 订阅 columnsFlow，
 * 设置切换后即时重组生效，无需重启。
 */
object GridSettings {

    const val DEFAULT_COLUMNS = 2

    private val KEY_COLUMNS = intPreferencesKey("grid_columns")

    private lateinit var appContext: Context

    /** 后台写盘作用域 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _columnsFlow = MutableStateFlow(DEFAULT_COLUMNS)

    /** 作品网格每行数量（2 或 3），供 Compose collectAsState 订阅 */
    val columnsFlow: StateFlow<Int> = _columnsFlow

    fun init(context: Context) {
        appContext = context.applicationContext
        ioScope.launch {
            val stored = runCatching {
                appContext.uiDataStore.data.first()[KEY_COLUMNS]
            }.getOrNull()
            _columnsFlow.value = sanitize(stored)
        }
    }

    fun setColumns(value: Int) {
        val v = sanitize(value)
        _columnsFlow.value = v
        ioScope.launch {
            runCatching {
                appContext.uiDataStore.edit { it[KEY_COLUMNS] = v }
            }
        }
    }

    private fun sanitize(v: Int?): Int = if (v == 2 || v == 3) v else DEFAULT_COLUMNS
}
