package com.jmread.core.library

import com.jmread.core.model.ComicSummary
import com.jmread.data.Bookmarks
import com.jmread.data.ReadStatus
import com.jmread.data.RecentRead

/**
 * 书架条目：UI 唯一消费的形状（docs/unified-library-design.md §3.1）。
 *
 * 四源（本地书架/本地历史/云端收藏/云端历史）按漫画 id 并集去重后的产物，
 * 字段只保留用户可感知的状态——不携带、也不暴露任何「本地/云端」来源标记。
 */
data class LibraryItem(
    val comicId: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    /** 心形状态 = 本地书架 ∪ 云端收藏 */
    val collected: Boolean = false,
    /** 收藏时间（本地书架的 addedAt；云端收藏无此数据时为 null） */
    val collectedAt: Long? = null,
    /** 最后阅读时间（本地历史 ts；云端历史无可信时间字段时为 null，见 P2 探针） */
    val lastReadAt: Long? = null,
    /** 续读章节（本地历史携带；云端-only 条目为 null → 点卡片进详情） */
    val lastOrder: Int? = null,
    val lastPage: Int? = null,
    /** 读完标记（ReaderStatus.FINISHED，优先级高于在读） */
    val finished: Boolean = false,
    /** 有更新角标（仅云端收藏条目的 latest_ep 可判时为 true，不伪造本地数据） */
    val hasUpdate: Boolean = false,
) {
    /** 最近动态排序键：max(最后阅读时间, 收藏时间)；全空为 0（沉底且保持服务端相对序） */
    val lastActivityAt: Long get() = maxOf(lastReadAt ?: 0L, collectedAt ?: 0L)

    /** 卡片状态行：优先级 读完 > 续读 > 无 */
    val statusLine: String?
        get() = when {
            finished -> "已读完"
            lastOrder != null -> "续读 · 第 $lastOrder 话 · 第 ${(lastPage ?: 0) + 1} 页"
            else -> null
        }

    fun toSummary(): ComicSummary = ComicSummary(
        id = comicId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        // 有更新角标数据源：非空即显示（ComicGridView 契约）
        latestEpText = if (hasUpdate) "有更新" else "",
    )
}

/** 书架筛选 chips：状态维度，不是来源维度（设计红线：入口内不区分本地/云端） */
enum class LibraryFilter(val label: String) {
    ALL("全部"),
    COLLECTED("收藏"),
    READING("在读"),
    FINISHED("读完"),
    ;

    fun matches(item: LibraryItem): Boolean = when (this) {
        ALL -> true
        COLLECTED -> item.collected
        READING -> item.lastOrder != null && !item.finished
        FINISHED -> item.finished
    }
}

/**
 * 四源并集合成的纯函数（JVM 单测覆盖）。
 *
 * 字段权威级：进度/时间以本地历史为权威；收藏态以并集为权威；
 * 元数据（标题/作者/封面）云端列表（服务端每次返回最新）优先，本地兜底。
 */
object LibraryMerge {

    fun merge(
        bookmarks: List<Bookmarks.BookmarkItem>,
        recents: List<RecentRead>,
        cloudFavourites: List<ComicSummary>,
        cloudHistory: List<ComicSummary>,
        statusOf: (String) -> ReadStatus?,
    ): List<LibraryItem> {
        val byId = LinkedHashMap<String, LibraryItem>()

        // 1. 云端收藏：服务端序（o=mr = 收藏时间倒序），稳定排序下保留其相对次序
        cloudFavourites.forEach { c ->
            byId[c.id] = LibraryItem(
                comicId = c.id,
                title = c.title,
                author = c.author,
                coverUrl = c.coverUrl,
                collected = true,
                hasUpdate = c.latestEpText.isNotBlank(),
            )
        }

        // 2. 云端历史：只读导入。已在收藏里的条目仅补齐缺失元数据，不新增字段
        cloudHistory.forEach { c ->
            val prev = byId[c.id]
            byId[c.id] =
                if (prev == null) {
                    LibraryItem(comicId = c.id, title = c.title, author = c.author, coverUrl = c.coverUrl)
                } else {
                    prev.copy(
                        title = prev.title.ifBlank { c.title },
                        author = prev.author.ifBlank { c.author },
                        coverUrl = prev.coverUrl ?: c.coverUrl,
                    )
                }
        }

        // 3. 本地书架：收藏态的本地权威（addedAt 可信）
        bookmarks.forEach { b ->
            val prev = byId[b.comicId]
            byId[b.comicId] =
                (prev ?: LibraryItem(comicId = b.comicId, title = b.title, author = b.author, coverUrl = b.coverUrl.ifBlank { null }))
                    .copy(
                        collected = true,
                        collectedAt = b.addedAt,
                        title = prev?.title?.takeIf { it.isNotBlank() } ?: b.title,
                        author = prev?.author?.takeIf { it.isNotBlank() } ?: b.author,
                        coverUrl = prev?.coverUrl ?: b.coverUrl.ifBlank { null },
                    )
        }

        // 4. 本地阅读历史：进度/时间权威（精确到话+页，续读永远以它为准）
        recents.forEach { r ->
            val prev = byId[r.comicId]
            byId[r.comicId] =
                (prev ?: LibraryItem(comicId = r.comicId, title = r.title, author = r.author, coverUrl = r.coverUrl.ifBlank { null }))
                    .copy(
                        lastReadAt = r.ts,
                        lastOrder = r.order,
                        lastPage = r.pageIndex,
                        title = prev?.title?.takeIf { it.isNotBlank() } ?: r.title,
                        author = prev?.author?.takeIf { it.isNotBlank() } ?: r.author,
                        coverUrl = prev?.coverUrl ?: r.coverUrl.ifBlank { null },
                    )
        }

        // 5. 读完状态（ReaderStatus 内存缓存；只升不降）
        val items = byId.values.map { item ->
            if (statusOf(item.comicId) == ReadStatus.FINISHED) item.copy(finished = true) else item
        }

        return items.sortedWith(
            compareByDescending<LibraryItem> { it.lastActivityAt }
                .thenByDescending { it.collectedAt ?: 0L },
            // 无 comicId 决胜键：sortWith 稳定排序保留插入序——
            // 云端条目的插入序即服务端序（收藏时间/浏览时间倒序、懒加载页序），比 id 序更有意义
        )
    }
}
