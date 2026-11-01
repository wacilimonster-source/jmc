package com.jmread.ui.library

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.jmread.core.library.LibraryRepository
import com.jmread.core.log.LogStore
import com.jmread.data.ReadHistorySnapshot
import com.jmread.ui.common.AppSnackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 书架清除阅读记录 + 10 秒撤销（P1-08）。
 *
 * - 应用级宿主：导航离开仍可见至期限结束；
 * - 连续清除不同作品：同一窗口内合并快照，文案显示“已清除 N 本”；
 * - 撤销按作品修订号原子合并，保留清除后新增的进度/读完/历史。
 */
object ReadHistoryUndo {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snapshots = mutableListOf<ReadHistorySnapshot>()
    private var job: Job? = null

    fun clearAndOffer(comicId: String, title: String) {
        scope.launch {
            val snapshot = runCatching {
                LibraryRepository.clearReadHistory(comicId)
            }.getOrNull() ?: return@launch
            snapshots += snapshot
            LogStore.log(
                "Undo", "I",
                "clear comic=$comicId recent=${snapshot.recentRead?.comicId} " +
                    "progress=${snapshot.progress} finished=${snapshot.finished}",
            )
            job?.cancel()
            job = scope.launch {
                val count = snapshots.size
                val result = try {
                    withTimeoutOrNull(AppSnackbar.undoTimeoutMs) {
                        AppSnackbar.hostState.showSnackbar(
                            message = if (count == 1) {
                                "已清除《${title.ifBlank { "未知作品" }}》阅读记录"
                            } else {
                                "已清除 $count 本阅读记录"
                            },
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                } finally {
                    // 超时/被新一次清除取消时，把当前提示收掉，避免残留。
                    // 动作触发时 showSnackbar 已自行 dismiss，这里必须容错，不能阻断恢复逻辑。
                    runCatching { AppSnackbar.hostState.currentSnackbarData?.dismiss() }
                }
                val toRestore = if (result == SnackbarResult.ActionPerformed) {
                    snapshots.toList()
                } else {
                    emptyList()
                }
                LogStore.log(
                    "Undo", "I",
                    "snackbar result=$result restore=${toRestore.size}",
                )
                snapshots.clear()
                toRestore.forEach {
                    LogStore.log("Undo", "I", "restore comic=${it.comicId} recent=${it.recentRead?.comicId}")
                    LibraryRepository.restoreReadHistory(it)
                }
            }
        }
    }
}
