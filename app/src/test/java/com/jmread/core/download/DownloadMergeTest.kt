package com.jmread.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-06：恢复清单按 key 合并，current 优先，本轮删除的 key 不复活。 */
class DownloadMergeTest {

    private fun runtime(comicId: String, order: Int, status: DlStatus = DlStatus.PENDING) =
        TaskRuntime(
            task = DownloadTask(
                comicId = comicId,
                comicTitle = "作品$comicId",
                coverUrl = "",
                order = order,
                epTitle = "第 $order 话",
                pageCount = 3,
            ),
            status = status,
        )

    @Test
    fun `current 优先且不与 restored 重复`() {
        val current = runtime("1", 1, DlStatus.DOWNLOADING)
        val restoredOld = runtime("1", 1, DlStatus.FAILED)
        val restoredNew = runtime("1", 2, DlStatus.COMPLETED)

        val merged = mergeRestoredTasks(
            current = listOf(current),
            restored = listOf(restoredOld, restoredNew),
            deletedKeys = emptySet(),
        )

        assertEquals(2, merged.size)
        assertEquals(DlStatus.DOWNLOADING, merged.first { it.key == "1#1" }.status)
        assertEquals(DlStatus.COMPLETED, merged.first { it.key == "1#2" }.status)
    }

    @Test
    fun `本轮删除的 key 不恢复`() {
        val merged = mergeRestoredTasks(
            current = emptyList(),
            restored = listOf(runtime("1", 1), runtime("1", 2)),
            deletedKeys = setOf("1#2"),
        )
        assertEquals(listOf("1#1"), merged.map { it.key })
    }

    @Test
    fun `空清单不产生任务`() {
        assertTrue(mergeRestoredTasks(emptyList(), emptyList(), emptySet()).isEmpty())
        assertFalse(mergeRestoredTasks(emptyList(), emptyList(), setOf("1#1")).isNotEmpty())
    }
}
