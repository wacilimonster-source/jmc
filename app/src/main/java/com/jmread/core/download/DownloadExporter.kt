package com.jmread.core.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jmread.BuildConfig
import com.jmread.core.log.LogStore
import com.jmread.core.scramble.ImageFormatDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 下载导出 ZIP（P0-06）。
 *
 * 设计要点（修订版）：
 * - 开始前冻结章节目录与页清单，并逐项校验文件完整；缺页/零字节/不可读立即失败，
 *   不允许静默跳页后仍提示“已导出”。
 * - 直接写 SAF 目标；取消/失败先关闭流并尽力删除目标，删除失败要求 UI 明示“文件可能不完整”。
 * - 扩展名/MIME 以文件魔数为准；章节目录用 %04d 保证排序。
 * - 只有 ZIP 收尾且流关闭成功才进入 Done。
 */
object DownloadExporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var job: Job? = null

    @Serializable
    data class ExportMeta(
        val schemaVersion: Int = 1,
        val comicId: String,
        val comicTitle: String,
        val author: String = "",
        val exportedAt: Long,
        val appVersion: String,
        val chapters: List<ExportChapterMeta>,
    )

    @Serializable
    data class ExportChapterMeta(
        val order: Int,
        val title: String,
        val pageCount: Int,
        val dir: String,
    )

    sealed interface ExportState {
        data class Running(
            val comicId: String,
            val fileName: String,
            val comicTitle: String,
            val chapterIndex: Int,
            val totalChapters: Int,
            val chapterTitle: String,
            val copiedBytes: Long = 0L,
        ) : ExportState

        data class Done(
            val fileName: String,
            val chapters: Int,
            val uri: Uri,
        ) : ExportState

        data class Error(val message: String) : ExportState
    }

    private val _state = MutableStateFlow<ExportState?>(null)
    val state: StateFlow<ExportState?> = _state.asStateFlow()

    /** @return false = 已有导出在进行 */
    fun start(context: Context, comicId: String, uri: Uri): Boolean {
        if (job?.isActive == true) return false
        _state.value = null
        job = scope.launch {
            mutex.withLock {
                runExport(context.applicationContext, comicId, uri)
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** 终态提示展示后由 UI 置空，避免重进页面重复弹 */
    fun consume() {
        _state.value = null
    }

    private suspend fun runExport(context: Context, comicId: String, uri: Uri) {
        val runtimes = DownloadManager.comicTasks(comicId).filter { it.isFinished }
        if (runtimes.isEmpty()) {
            _state.value = ExportState.Error("没有已完成的章节可导出")
            return
        }
        val comicTitle = runtimes.first().task.comicTitle.ifBlank { comicId }
        val fileName = displayNameOf(context, uri)
        val rootInZip = sanitize(comicTitle).take(50).ifBlank { comicId }
        val chapters = runtimes.sortedBy { it.task.order }.map { rt ->
            val title = rt.task.epTitle.ifBlank { "第 ${rt.task.order} 话" }
            FrozenChapter(
                order = rt.task.order,
                title = title,
                dir = "%04d".format(Locale.US, rt.task.order) + "-" + sanitize(title).take(80),
                files = (0 until rt.task.pageCount).map { i ->
                    DownloadManager.pageFile(comicId, rt.task.order, i)
                },
            )
        }

        // 冻结后逐项校验：缺页/零字节直接失败，不写半个 ZIP
        chapters.forEach { chapter ->
            if (chapter.files.isEmpty()) {
                _state.value = ExportState.Error("第 ${chapter.order} 话页数未知，请重新下载后再导出")
                return
            }
            chapter.files.forEachIndexed { index, file ->
                if (!file.isFile || file.length() <= 0L) {
                    _state.value = ExportState.Error(
                        "第 ${chapter.order} 话第 ${index + 1} 页缺失，请修复下载后重试",
                    )
                    return
                }
            }
        }

        val meta = ExportMeta(
            comicId = comicId,
            comicTitle = comicTitle,
            author = runtimes.first().task.author,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            chapters = chapters.map {
                ExportChapterMeta(
                    order = it.order,
                    title = it.title,
                    pageCount = it.files.size,
                    dir = it.dir,
                )
            },
        )
        var out: java.io.OutputStream? = null
        try {
            out = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("无法写入所选位置，请换一个目录重试")
            val zip = ZipOutputStream(BufferedOutputStream(out))
            try {
                zip.putNextEntry(ZipEntry("$rootInZip/jm.json"))
                zip.write(json.encodeToString(ExportMeta.serializer(), meta).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                var copied = 0L
                chapters.forEachIndexed { chapterIndex, chapter ->
                    _state.value = ExportState.Running(
                        comicId = comicId,
                        fileName = fileName,
                        comicTitle = comicTitle,
                        chapterIndex = chapterIndex + 1,
                        totalChapters = chapters.size,
                        chapterTitle = chapter.title,
                        copiedBytes = copied,
                    )
                    chapter.files.forEachIndexed { pageIndex, file ->
                        ensureActive()
                        if (!file.isFile || file.length() <= 0L) {
                            throw IOException(
                                "第 ${chapter.order} 话第 ${pageIndex + 1} 页在导出时不可读",
                            )
                        }
                        val format = runCatching {
                            file.inputStream().use { input ->
                                val head = ByteArray(16)
                                val n = input.read(head)
                                if (n <= 0) throw IOException("空文件")
                                ImageFormatDetector.detect(head.copyOf(n))
                            }
                        }.getOrElse {
                            throw IOException("第 ${chapter.order} 话第 ${pageIndex + 1} 页无法读取")
                        }
                        val entryName = "$rootInZip/chapters/${chapter.dir}/" +
                            "page_%03d.%s".format(Locale.US, pageIndex + 1, format.extension)
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n <= 0) break
                                zip.write(buffer, 0, n)
                                copied += n
                            }
                        }
                        zip.closeEntry()
                    }
                }
                zip.finish()
            } finally {
                // 关闭失败必须冒泡：目标流未成功收尾时不得报告“已导出”
                zip.close()
            }
            // 只有上面的 close 成功（out 被关闭）才进入 Done
            _state.value = ExportState.Done(fileName, chapters.size, uri)
        } catch (ce: CancellationException) {
            cleanupTarget(context, uri)
            _state.value = ExportState.Error("已取消，文件可能不完整")
            throw ce
        } catch (e: Exception) {
            cleanupTarget(context, uri)
            LogStore.log("DownloadExporter", "E", "export failed: ${e.message}")
            _state.value = ExportState.Error(
                if (e is IOException) "导出失败：${e.message ?: "目标可能已满"}"
                else "导出失败：${e.message ?: "未知错误"}"
            )
        } finally {
            runCatching { out?.close() }
        }
    }

    private suspend fun ensureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    private fun cleanupTarget(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun displayNameOf(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { "JM Reader 导出.zip" }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\n\r\\x00-\\x1f]"), "_").trim().ifBlank { "JM" }

    private data class FrozenChapter(
        val order: Int,
        val title: String,
        val dir: String,
        val files: List<File>,
    )
}
