package com.jmread.core

import android.content.Context
import coil.Coil
import com.jmread.core.scramble.ScrambleCache
import com.jmread.data.WebtoonSliceCache
import java.io.File

/**
 * 缓存体量统计与一键清空（设置页「清除缓存」入口）。
 *
 * 覆盖三类可重建缓存，全部不含用户数据：
 *   1. Coil 图片缓存（磁盘 + 内存）——封面/章节原图
 *   2. ScrambleCache —— 乱序判定表与占比统计
 *   3. WebtoonSliceCache —— 条漫长图切片数记录
 *
 * 体积统计走目录遍历；Coil 2.7 的 DiskCache.directory 是 okio.Path，转 File 后复用 FileTreeWalk。
 */
object CacheCleaner {

    data class Snapshot(
        val imageCacheBytes: Long,
        val scrambleEntries: Int,
        val sliceChapters: Int,
    ) {
        fun text(): String = buildString {
            append("图片缓存 ${formatBytes(imageCacheBytes)}")
            append(" · 乱序判定 ${scrambleEntries} 条")
            append(" · 切片记录 ${sliceChapters} 章")
        }
    }

    fun snapshot(context: Context): Snapshot {
        val bytes = runCatching {
            val dir = Coil.imageLoader(context).diskCache?.directory?.toString() ?: return@runCatching 0L
            File(dir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrNull() ?: 0L
        return Snapshot(bytes, ScrambleCache.size(), WebtoonSliceCache.chapterCount())
    }

    /** 清空全部可重建缓存；耗时操作（磁盘遍历/删除），调用方须放在 IO 协程 */
    fun clear(context: Context) {
        runCatching {
            val loader = Coil.imageLoader(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        ScrambleCache.reset()
        WebtoonSliceCache.clear(context)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
