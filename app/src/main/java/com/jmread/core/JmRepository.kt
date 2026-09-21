package com.jmread.core

import com.jmread.core.model.BROWSE_HARD_CAP
import com.jmread.core.model.ComicCategory
import com.jmread.core.model.ComicChapter
import com.jmread.core.model.ComicComment
import com.jmread.core.model.ComicDetail
import com.jmread.core.model.ComicPage
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSubCategory
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.ComicUser
import com.jmread.core.model.DailyCheckIn
import com.jmread.core.model.PageResult
import com.jmread.core.model.RankTab
import com.jmread.core.model.WeekPeriod
import com.jmread.core.model.displayUpdatedAt
import com.jmread.core.model.pagesOf
import com.jmread.core.model.pickNewArrivals
import com.jmread.data.SecureAccountStore
import com.jmread.data.SourcePrefs
import com.jmread.network.JmAlbumSummary
import com.jmread.network.JmCategoriesResponse
import com.jmread.network.JmClient
import com.jmread.network.JmCrypto
import com.jmread.network.JmException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 唯一数据门面：DTO → 领域模型映射 + 分页判据 + 去重 + 短 TTL 缓存。
 *
 * 单源 App，不再有 SourceManager / Source 接口；UI 全部直连本对象。
 * 能力未开放的方法（收藏写入 / 签到 / 云端历史等）保留实现但由
 * [JmCapabilities] 的 false 值保证 UI 不渲染入口；验证后把能力位翻 true 即可用。
 */
object JmRepository {

    // ---------- album 短 TTL 内存缓存（详情页会以同一 id 请求 2~3 次） ----------
    private val albumCache = ConcurrentHashMap<String, Pair<Long, com.jmread.network.JmAlbumResponse>>()
    private const val ALBUM_TTL_MS = 120_000L

    /** 「新晋热榜」热度池页数（月榜前 2 页 = 160 条） */
    private const val NEW_ARRIVAL_POOL_PAGES = 2
    /** 「新晋热榜」展示条数 */
    private const val NEW_ARRIVAL_LIMIT = 40
    /** 按最近更新排序后的条数低于此值就回退成月榜原序，避免出现稀疏列表 */
    private const val NEW_ARRIVAL_MIN = 12
    /** 榜单档位能力探测的缓存时长（与 /setting 刷新同频） */
    private const val RANK_PROBE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 榜单档位探测结果缓存：(探测时刻, 可用档位) */
    @Volatile private var rankProbeCache: Pair<Long, List<RankTab>>? = null

    private suspend fun albumCached(albumId: String): com.jmread.network.JmAlbumResponse {
        val now = System.currentTimeMillis()
        albumCache[albumId]?.let { (at, resp) ->
            if (now - at < ALBUM_TTL_MS) return resp
        }
        val resp = JmClient.album(albumId)
        albumCache[albumId] = now to resp
        albumCache.entries.removeIf { now - it.value.first >= ALBUM_TTL_MS }
        return resp
    }

    // ---------- 登录态 ----------

    val isLoggedIn: Boolean get() = SourcePrefs.current().isLoggedIn
    val accountName: String? get() = SourcePrefs.current().jmAccount

    /** 由 JmApp.onCreate 调用：装配 401 静默重登钩子 */
    fun init() {
        JmClient.onUnauthorizedHook = {
            val cred = SecureAccountStore.load()
            if (cred != null) {
                runCatching { JmClient.login(cred.first, cred.second) }
                    .onSuccess { SourcePrefs.current().setJmLogin(it, cred.first) }
                    .onFailure { SourcePrefs.current().clearJmLogin() }
            } else {
                SourcePrefs.current().clearJmLogin()
            }
        }
    }

    suspend fun login(account: String, password: String) {
        val session = JmClient.login(account, password)
        SourcePrefs.current().setJmLogin(session, account.trim())
        SecureAccountStore.save(account.trim(), password)
    }

    suspend fun logout() {
        runCatching { JmClient.logout() }
        SourcePrefs.current().clearJmLogin()
    }

    /** 启动/换域后的 /setting 刷新（失败静默：内置域名表兜底） */
    suspend fun launchSettingRefresh() {
        runCatching { JmClient.refreshSetting(force = true) }
    }

    // ---------- 分类 / 标签 ----------

    suspend fun categories(): List<ComicCategory> {
        val resp = runCatching { JmClient.categories() }.getOrDefault(JmCategoriesResponse())
        return resp.data.categories.map {
            ComicCategory(
                id = it.slug.ifBlank { it.id },
                title = it.name.ifBlank { it.id },
                coverUrl = null,
                totalCount = it.totalAlbums,
                subCategories = it.subCategories.map { s -> ComicSubCategory(s.slug, s.name) },
            )
        }
    }

    /** 完整分类数据：大类 + 二级分类 + blocks 官方标签（标签墙） */
    suspend fun categoryTree(): List<JmCategoryNode> {
        val resp = runCatching { JmClient.categories() }.getOrDefault(JmCategoriesResponse())
        return resp.data.categories.map { c ->
            JmCategoryNode(
                id = c.slug.ifBlank { c.id },
                title = c.name.ifBlank { c.id },
                totalCount = c.totalAlbums,
                subCategories = c.subCategories.map { JmSubCategoryNode(it.slug, it.name) },
            )
        }
    }

    /** 官方标签墙：4 组 43 个标签（/categories.blocks，实测可用） */
    suspend fun tagGroups(): List<TagGroup> {
        val resp = runCatching { JmClient.categories() }.getOrDefault(JmCategoriesResponse())
        return resp.data.blocks.map { TagGroup(it.title, it.content) }
    }

    suspend fun tags(): List<String> = tagGroups().flatMap { it.tags }

    suspend fun hotWords(): List<String> = emptyList()

    // ---------- 列表 ----------

    /**
     * 浏览 / 分类流。
     * @param category 大类或子分类 slug（c= 参数实测生效）
     */
    suspend fun browse(
        page: Int,
        category: String? = null,
        sort: ComicSort = ComicSort.DD,
    ): PageResult<ComicSummary> {
        val data = JmClient.browse(page, category, sort)
        val items = data.data.items.map { it.toSummary() }
        // total 恒 10000：pagesOf 判为不可信 → 按当页条数 + 125 硬上限
        val pages = pagesOf(data.data.total, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
        return PageResult(items.dedupeById(), page, pages, total = 0)
    }

    /**
     * 搜索（综合维度入口；四维 Tab 用 [searchDimension]）。
     * 分类过滤取多选的第一个 slug（c= 单值参数）。
     */
    suspend fun search(
        keyword: String,
        page: Int,
        sort: ComicSort = ComicSort.DD,
        categories: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        author: String? = null,
        chineseTeam: String? = null,
        uploader: String? = null,
        finished: Boolean? = null,
    ): PageResult<ComicSummary> =
        searchDimension(keyword, page, sort, mainTag = 0, category = categories.firstOrNull())

    /**
     * 四维搜索：main_tag 0 综合 / 1 作品 / 2 作者 / 3 标签（实测各有独立结果与真实 total）。
     * 汉化组 / 上传者 / 完结筛选移动端无参数，签名保留、实现忽略（能力位保证 UI 不出现）。
     */
    suspend fun searchDimension(
        keyword: String,
        page: Int,
        sort: ComicSort = ComicSort.DD,
        mainTag: Int = 0,
        category: String? = null,
    ): PageResult<ComicSummary> {
        val data = JmClient.search(keyword, page, sort, mainTag, category)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    /**
     * 排行榜。
     *
     * H24（新晋热榜）不是服务端档位：日榜 `o=mv_t` 实测恒空，见 [newArrivals]。
     * D7/D30 走服务端 `o=mv_w` / `o=mv_m`，total 真实。
     */
    suspend fun rank(type: String, page: Int = 1): PageResult<ComicSummary> {
        val tab = RankTab.of(type)
        val order = tab.order ?: return newArrivals()
        return serverRank(order, page)
    }

    private suspend fun serverRank(order: String, page: Int): PageResult<ComicSummary> {
        val data = JmClient.rankList(order, page)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    /**
     * 新晋热榜：热门池里「最近有更新」的作品。
     *
     * 为什么这么实现：禁漫的日榜端点 `o=mv_t` 已死（total=0，连测稳定），
     * 而 `t=` 时间段参数在移动端接口上完全无效（t=t/w/m/a 四值返回结果一模一样），
     * 所以拿不到真正的「今日」榜单。改用月榜前 2 页当热度池（160 条），
     * 按 `update_at` 倒序取前 [NEW_ARRIVAL_LIMIT] 条 —— 实测该池内近 24h 有更新 29 条、
     * 近 7 天 108 条，取 40 条刚好覆盖最近一两天，语义上最接近「日榜」。
     *
     * 保证非空：池子拿到但排序结果不足下限时，回退成月榜原始顺序，
     * 避免又出现首屏空白（F1 复发）。
     */
    private suspend fun newArrivals(): PageResult<ComicSummary> {
        val pool = ArrayList<JmAlbumSummary>()
        for (page in 1..NEW_ARRIVAL_POOL_PAGES) {
            val items = runCatching { JmClient.rankList("mv_m", page).data.items }.getOrNull()
            if (items != null) pool.addAll(items)
        }
        val items = pickNewArrivals(
            pool = pool.distinctBy { it.id }.map { it.toSummary() },
            limit = NEW_ARRIVAL_LIMIT,
            min = NEW_ARRIVAL_MIN,
        )
        return PageResult(items.dedupeById(), 1, 1, total = items.size)
    }

    /**
     * 榜单档位能力探测：哪些档位线上真的有数据。
     *
     * 存在的理由：档位写死过一次就出过空 tab（日榜 mv_t 恒空，首屏一片空白）。
     * 探测结果缓存 [RANK_PROBE_TTL_MS]，与 /setting 刷新同频；失败时乐观返回全部档位，
     * 宁可多显示一个可能为空的档位，也不要因为探测失败把能用的档位也藏起来。
     */
    suspend fun availableRankTabs(force: Boolean = false): List<RankTab> {
        val now = System.currentTimeMillis()
        rankProbeCache?.let { (at, cached) ->
            if (!force && now - at < RANK_PROBE_TTL_MS) return cached
        }
        val hasMonth = runCatching { serverRank("mv_m", 1).items.isNotEmpty() }.getOrDefault(false)
        val hasWeek = runCatching { serverRank("mv_w", 1).items.isNotEmpty() }.getOrDefault(false)
        // 新晋热榜的数据来自月榜，月榜有它就一定非空
        val tabs = buildList {
            if (hasMonth) add(RankTab.H24)
            if (hasWeek) add(RankTab.D7)
            if (hasMonth) add(RankTab.D30)
        }
        val result = tabs.ifEmpty { RankTab.optimistic }
        rankProbeCache = now to result
        return result
    }

    /** 随机推荐：本源无该端点（/random Not legal）——能力位 false，UI 不出现 */
    suspend fun randomComics(): List<ComicSummary> {
        throw UnsupportedOperationException("禁漫无随机端点")
    }

    /** 每周必看期数列表 */
    suspend fun weeklyPeriods(): List<WeekPeriod> {
        val resp = JmClient.week()
        return resp.data.categories.map { WeekPeriod(id = it.id, label = it.time.ifBlank { it.title }) }
    }

    /** 每周必看某期内容流 */
    suspend fun weeklyContent(weekId: String, page: Int): PageResult<ComicSummary> {
        val data = JmClient.weekFilter(weekId, page)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    // ---------- 详情 / 章节 / 阅读 ----------

    suspend fun comicDetail(id: String): ComicDetail {
        val a = albumCached(id).data
        val albumId = a.id.takeIf { it > 0 }?.toString() ?: id
        val authors = a.author.filter { it.isNotBlank() }
        return ComicDetail(
            id = albumId,
            title = a.name,
            author = authors.joinToString(" / ").ifBlank { "佚名" },
            authors = authors,
            description = a.description.orEmpty(),
            coverUrl = JmCrypto.coverUrl(albumId),
            tags = a.tags,
            works = a.works,
            actors = a.actors,
            finished = false,
            pagesCount = a.totalPhotos,
            epsCount = a.series.size.coerceAtLeast(1),
            totalViews = a.totalViews.toLongSafe(),
            totalLikes = a.likes.toLongSafe(),
            commentsCount = a.commentTotal.toLongSafe(),
            createdAt = a.addtime,
            isFavourite = a.isFavorite,
            isPaid = a.price.isNotBlank(),
        )
    }

    suspend fun chapters(id: String): List<ComicChapter> {
        val a = albumCached(id).data
        val albumId = a.id.takeIf { it > 0 }?.toString() ?: id
        return if (a.series.isNotEmpty()) {
            // 按 sort 字段排序（线上顺序不能按数组下标），空名回退「第 N 话」
            a.series
                .mapIndexed { i, s -> Triple(s, s.sort.toIntOrNull() ?: (i + 1), i) }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .mapIndexed { idx, (s, _, _) ->
                    ComicChapter(id = s.id, title = s.name.ifBlank { "第 ${idx + 1} 话" }, order = idx + 1)
                }
        } else {
            // 单章本子：series 为空，章节即本子自身
            listOf(ComicChapter(id = albumId, title = "第 1 话", order = 1))
        }
    }

    suspend fun chapterPages(comicId: String, order: Int): List<ComicPage> {
        val a = albumCached(comicId).data
        val albumId = a.id.takeIf { it > 0 }?.toString() ?: comicId
        // order(1-based) -> 章节 photo id；单章本子回退用 album id
        val sorted = a.series
            .mapIndexed { i, s -> Triple(s, s.sort.toIntOrNull() ?: (i + 1), i) }
            .sortedWith(compareBy({ it.second }, { it.third }))
        val photoId = sorted.getOrNull(order - 1)?.first?.id ?: albumId
        val chapter = JmClient.chapter(photoId).data
        return chapter.images.mapIndexed { i, filename ->
            ComicPage(index = i, imageUrl = JmCrypto.imageUrl(photoId, filename))
        }
    }

    /** 相关推荐：详情的 related_list（约 12 条），带封面 */
    suspend fun recommendations(id: String): List<ComicSummary> {
        val a = albumCached(id).data
        return a.relatedList.map {
            ComicSummary(
                id = it.id,
                title = it.name,
                author = it.author,
                coverUrl = JmCrypto.coverUrl(it.id),
            )
        }
    }

    // ---------- 评论 ----------

    suspend fun comments(comicId: String, page: Int): PageResult<ComicComment> {
        val data = JmClient.comments(comicId, page).data
        val items = data.list.map { it.toComment() }
        val pages = pagesOf(data.total, items.size, page, COMMENT_PAGE_SIZE, 500)
        return PageResult(items, page, pages, total = data.total)
    }

    /** 发评论：能力位 false（参数与风控未验证），UI 不渲染输入入口 */
    suspend fun sendComment(comicId: String, content: String): Boolean {
        throw UnsupportedOperationException("发评论能力未验证，未开放")
    }

    // ---------- 需登录（能力位未开，验证后启用） ----------

    suspend fun favourites(page: Int): PageResult<ComicSummary> {
        val data = JmClient.favorites(page)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, FAV_PAGE_SIZE, 500)
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    /** 收藏/取消收藏：参数未验证（取消字段是社区猜测），能力位 false 期间不得调用 */
    suspend fun favourite(comicId: String, add: Boolean): Boolean {
        throw UnsupportedOperationException("云端收藏写入能力未验证，未开放")
    }

    /** 签到。免登录时 data=null 是「未登录」不是「未签到」 */
    suspend fun dailyCheckIn(): DailyCheckIn {
        val resp = runCatching { JmClient.dailyCheckIn() }.getOrNull()
            ?: return DailyCheckIn(message = "网络异常")
        if (!isLoggedIn) return DailyCheckIn(message = "未登录")
        val d = resp.data
        return DailyCheckIn(
            checkedIn = d?.isCheckIn ?: false,
            consecutiveDays = d?.checkInDays ?: d?.days ?: 0,
            message = resp.errorMsg ?: "",
        )
    }

    suspend fun cloudHistory(page: Int): PageResult<ComicSummary> {
        val data = JmClient.watchList(page)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    // ---------- 内部 ----------

    private val PAGE_SIZE = 80
    private val FAV_PAGE_SIZE = 80
    private val COMMENT_PAGE_SIZE = 20

    private fun com.jmread.network.JmAlbumSummary.toSummary() = ComicSummary(
        id = id,
        title = name,
        author = author,
        coverUrl = JmCrypto.coverUrl(id),
        isFavourite = isFavorite,
        updatedAt = displayUpdatedAt(updateAt, adddate),
        // 原始 update_at 单独保留，供「新晋热榜」按最近更新排序
        lastUpdatedAt = updateAt,
        categoryName = category.titleText,
    )

    private fun com.jmread.network.JmForumComment.toComment(): ComicComment {
        val plain = stripHtml(content)
        val user = ComicUser(
            id = username,
            name = nickname.ifBlank { username.ifBlank { "匿名" } },
            avatarUrl = photo.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it else "https://$it"
            },
            level = expinfo.level,
            levelName = expinfo.levelName,
        )
        val ad = isAdComment(plain)
        return ComicComment(
            id = cid,
            content = plain,
            user = user,
            createdAt = addtime,
            likesCount = likes.toIntOrNull() ?: 0,
            isSpoiler = spoiler == "1",
            isAd = ad,
            // 线上 badges 是 {content, name, id} 对象数组；这里只取展示名，
            // content（徽章图相对路径）留待真要渲染徽章时再接
            badges = expinfo.badges.map { it.name }.filter { it.isNotBlank() },
            replies = replys.map { it.toComment() }.map { r ->
                // 子评论同样过治理规则
                val p = stripHtml(r.content)
                r.copy(content = p, isAd = isAdComment(p))
            },
        )
    }

    /** 剥离 HTML 标签与多余空白（/forum content 是 HTML） */
    private fun stripHtml(html: String): String = html
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("</p>", "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /**
     * 导流广告识别（实测首条评论即破解资源广告）。
     * 只做形态治理不做内容审查：外链 / 网盘 / 破解-关键词命中的条目由 UI 灰显折叠。
     */
    private fun isAdComment(plain: String): Boolean {
        val lowered = plain.lowercase()
        val adKeywords = listOf(
            "http://", "https://", "www.", ".com", ".cc", ".net", ".xyz", ".top", ".vip",
            "网盘", "盘口", "夸克", "百度云", "阿里云盘", "防走丢", "破解", " Eternal", "tg://", "t.me",
        )
        return adKeywords.any { lowered.contains(it) }
    }

    private fun List<ComicSummary>.dedupeById(): List<ComicSummary> {
        val seen = HashSet<String>(size)
        return filter { seen.add(it.id) }
    }

    private fun String.toLongSafe(): Long = trim().toLongOrNull() ?: 0L
}

/** 完整分类节点（分类页树） */
data class JmCategoryNode(
    val id: String,
    val title: String,
    val totalCount: Int,
    val subCategories: List<JmSubCategoryNode>,
)

data class JmSubCategoryNode(val slug: String, val name: String)

data class TagGroup(val title: String, val tags: List<String>)
