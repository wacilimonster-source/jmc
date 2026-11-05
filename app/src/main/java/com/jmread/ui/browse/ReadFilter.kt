package com.jmread.ui.browse

import com.jmread.core.model.ComicSummary
import com.jmread.data.ReadStatus
import com.jmread.data.ReaderStatus

/** 阅读状态筛选：ALL=全部，UNREAD=只看未读，UNFINISHED=只看未读完（排除已读完） */
enum class ReadFilter { ALL, UNREAD, UNFINISHED }

/** 筛选选项（chip 标签） */
val readFilterOptions: List<Pair<ReadFilter, String>> = listOf(
    ReadFilter.ALL to "全部",
    ReadFilter.UNREAD to "只看未读",
    ReadFilter.UNFINISHED to "只看未读完",
)

/** 按阅读状态过滤（内存查询，O(n)）。已读状态按作品标识 `源_id` 记账 */
fun List<ComicSummary>.filterByRead(filter: ReadFilter): List<ComicSummary> = when (filter) {
    ReadFilter.ALL -> this
    ReadFilter.UNREAD -> filter { ReaderStatus.of(it.ref) == null }
    ReadFilter.UNFINISHED -> filter { ReaderStatus.of(it.ref) != ReadStatus.FINISHED }
}

/**
 * 本地阅读筛选分页（I04 阶段 B）：先过滤已加载集合，再按页切片。
 *
 * 旧实现「先按 20 条切片再过滤」在当前片全被过滤掉时会显示空页，
 * 即使其它分片仍有命中；本实现保证只要已加载集合里有匹配，翻页总能到达。
 * 调用方在「匹配数不足当前页所需」且未到末尾时继续补页（loadMore），
 * 失败时停止自动补页并显式重试，不得声称全站无结果。
 */
fun filterThenPage(
    loaded: List<ComicSummary>,
    filter: ReadFilter,
    page: Int,
    pageSize: Int = FILTER_PAGE_SIZE,
): List<ComicSummary> {
    if (filter == ReadFilter.ALL) return loaded
    val matched = loaded.filterByRead(filter)
    val from = (page - 1).coerceAtLeast(0) * pageSize
    if (from >= matched.size) return emptyList()
    return matched.subList(from, (from + pageSize).coerceAtMost(matched.size))
}

/** 过滤后集合的总页数（至少 1，供分页条展示与页码上限） */
fun filteredPageCount(matchedCount: Int, pageSize: Int = FILTER_PAGE_SIZE): Int =
    ((matchedCount + pageSize - 1) / pageSize).coerceAtLeast(1)

/** 本地筛选分页的页大小（与服务端页大小解耦） */
const val FILTER_PAGE_SIZE = 20
