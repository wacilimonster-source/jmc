package com.jmread.core

import com.jmread.core.checkin.isSignedOn
import com.jmread.core.library.LibraryRepository
import com.jmread.core.log.LogStore
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
import com.jmread.core.model.PageResult
import com.jmread.core.model.RankTab
import com.jmread.core.model.WeekPeriod
import com.jmread.core.model.displayUpdatedAt
import com.jmread.core.model.formatUnixDate
import com.jmread.core.model.pagesOf
import com.jmread.core.model.pickNewArrivals
import com.jmread.data.SecureAccountStore
import com.jmread.data.SourcePrefs
import com.jmread.network.JmAlbumSummary
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

    /** 云端收藏 id 集合缓存：(探测时刻, ids)；收藏开关成功后置 null 作废 */
    @Volatile private var favIdsCache: Pair<Long, Set<String>>? = null
    private const val FAV_IDS_TTL_MS = 5 * 60 * 1000L

    /** 分类树缓存：站点结构固定，进程内复用（分类页/分类详情共用） */
    @Volatile private var categoriesCache: List<ComicCategory>? = null

    /**
     * 收藏状态版本：收藏/取消收藏成功、登录/退出后递增。
     * 收藏列表页返回前台时据此判断是否需要刷新，避免无脑重拉。
     */
    @Volatile
    var favouriteVersion: Long = 0L
        private set

    /**
     * 登录会话代际：登录/退出/凭据失效时递增。
     * 书架用它判断进程内缓存的云端数据是否仍属于当前账号。
     */
    @Volatile
    var sessionGeneration: Long = 0L
        private set

    /** 刚完成一次显式登录：由「我的」页消费，触发同步队列 flush 与 backfill 询问。 */
    @Volatile
    private var postLoginEvent: Boolean = false

    fun consumePostLoginEvent(): Boolean {
        if (!postLoginEvent) return false
        postLoginEvent = false
        return true
    }

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
        JmClient.onUnauthorizedHook = hook@{
            // 显式退出后不再自动重登：退出时可能仍有在途请求返回 401，
            // 若此时读取已保存凭据重新登录，用户会看到「退出了但账号又活了」。
            if (!isLoggedIn) return@hook
            // 单飞：多个并发请求同时 401 时只跑一次重登；login 本身已跳过该钩子，
            // 不存在 login -> 401 -> hook -> login 的递归。
            reloginMutex.withLock {
                if (!isLoggedIn) return@withLock
                val cred = SecureAccountStore.load()
                if (cred != null) {
                    try {
                        val data = JmClient.login(cred.first, cred.second)
                        SourcePrefs.current().setJmLogin(data.s, cred.first, data.uid)
                        favIdsCache = null
                        sessionGeneration++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 只有服务端明确拒绝凭据才登出；断网/超时/取消不应把会话清掉。
                        if ((e as? JmException)?.isCredentialRejected == true) {
                            SourcePrefs.current().clearJmLogin()
                            favIdsCache = null
                            sessionGeneration++
                            LibraryRepository.resetCloud()
                        } else {
                            LogStore.log(
                                "jm-net", "W",
                                "401 后重登失败（非凭据拒绝，保留会话）：${e.message?.take(80)}",
                            )
                        }
                    }
                } else {
                    // 用户选择「不保存账号密码」：无法自动重登，但也不能因为单个端点的 401
                    // 就把一个仍然有效的会话静默清掉（详情页拉收藏态就可能触发 401 限流）。
                    // 保留会话，让调用方把错误如实展示给用户。
                    LogStore.log(
                        "jm-net", "W",
                        "收到 401 且本机未保存凭据：保留当前会话，不自动登出",
                    )
                }
            }
        }
    }

    private val reloginMutex = Mutex()

    suspend fun login(account: String, password: String) {
        val data = JmClient.login(account, password)
        SourcePrefs.current().setJmLogin(data.s, account.trim(), data.uid)
        SecureAccountStore.save(account.trim(), password)
        // 换账号后旧的收藏 id 集合与书架云端缓存都不再成立
        favIdsCache = null
        favouriteVersion++
        sessionGeneration++
        postLoginEvent = true
        LibraryRepository.resetCloud()
    }

    suspend fun logout() {
        runCatching { JmClient.logout() }
        SourcePrefs.current().clearJmLogin()
        favIdsCache = null
        favouriteVersion++
        sessionGeneration++
        postLoginEvent = false
        LibraryRepository.resetCloud()
    }

    /** 启动/换域后的 /setting 刷新（失败静默：内置域名表兜底） */
    suspend fun launchSettingRefresh() {
        runCatching { JmClient.refreshSetting(force = true) }
    }

    // ---------- 分类 ----------

    /**
     * 分类树（进程内缓存）。
     *
     * 顶级分类与子分类是站点固定结构，重复进入分类页/分类详情不应重复请求；
     * 请求失败时回退到已有缓存，首次失败返回空列表（UI 显示加载中/空态）。
     */
    suspend fun categories(force: Boolean = false): List<ComicCategory> {
        val cached = categoriesCache
        if (!force && cached != null) return cached
        val resp = runCatching { JmClient.categories() }.getOrNull()
            ?: return cached ?: emptyList()
        val list = resp.data.categories.map {
            ComicCategory(
                id = it.slug.ifBlank { it.id },
                title = it.name.ifBlank { it.id },
                coverUrl = null,
                totalCount = it.totalAlbums,
                subCategories = it.subCategories.map { s -> ComicSubCategory(s.slug, s.name) },
            )
        }
        categoriesCache = list
        return list
    }

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
        // total 恒 10000：这是哨兵不是真实条数，必须传 0 让 pagesOf 走「当页不满」判据
        val pages = pagesOf(0, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
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
     * H24（新晋热榜）目前不是服务端档位：日榜 `o=mv_t` 曾在 2026-09 恒空，
     * 2026-10-25 金丝雀测得已复活（total≈100），但产品暂维持 [newArrivals] 的本地重排方案。
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
        val hasTotal = runCatching { serverRank("mv", 1).items.isNotEmpty() }.getOrDefault(false)
        // 新晋热榜的数据来自月榜，月榜有它就一定非空
        val tabs = buildList {
            if (hasMonth) add(RankTab.H24)
            if (hasTotal) add(RankTab.MV)
            if (hasMonth) add(RankTab.D30)
            if (hasWeek) add(RankTab.D7)
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
            // addtime 线上可能是 "2026-10-20" 这类日期串，也可能是裸 unix 秒（如 1792490572）；
            // 后者直接 take(10) 会显示成时间戳数字，必须归一化。
            createdAt = a.addtime.toLongOrNull()?.let { formatUnixDate(it) } ?: a.addtime,
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
        val sorted = a.series
            .mapIndexed { i, s -> Triple(s, s.sort.toIntOrNull() ?: (i + 1), i) }
            .sortedWith(compareBy({ it.second }, { it.third }))
        // order(1-based) -> 章节 photo id。只有单章本子（series 为空）才回退到 album id；
        // 越界 order 若也回退，会把整本封面页当成该章内容静默打开。
        val photoId = if (sorted.isEmpty()) {
            albumId
        } else {
            sorted.getOrNull(order - 1)?.first?.id
                ?: throw JmException("章节不存在：第 $order 话（共 ${sorted.size} 话）")
        }
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

    // ---------- 需登录（2026-09-25 实测定案，能力位已开） ----------

    /**
     * 云端收藏列表。排序 orderBy："mr" 收藏时间（默认）/"mp" 更新时间。
     * 列表项自带 latest_ep —— 「有更新」角标的数据源。
     */
    suspend fun favourites(page: Int, orderBy: String = "mr"): PageResult<ComicSummary> {
        val data = JmClient.favorites(page, orderBy)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, FAV_PAGE_SIZE, 500)
        // 单页即全集时顺手填充收藏 id 集合：详情页心形初值可直接命中缓存，
        // 少一次 /favorite 请求（该端点在高频连续请求下会返回 401）。
        if (page == 1 && data.data.total in 1..items.size) {
            favIdsCache = System.currentTimeMillis() to items.map { it.id }.toSet()
        }
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    /**
     * 云端收藏 id 集合（详情页心形初值 + 列表回填用）。
     *
     * /album.is_favorite 恒为 false（实测已收藏本也不变）——不可信；
     * 收藏态只能从 /favorite 列表来。TTL 缓存 5 分钟，收藏开关成功后失效。
     */
    suspend fun cloudFavouriteIds(force: Boolean = false): Set<String> {
        val now = System.currentTimeMillis()
        favIdsCache?.let { (at, ids) ->
            if (!force && now - at < FAV_IDS_TTL_MS) return ids
        }
        val ids = HashSet<String>()
        var page = 1
        while (true) {
            val r = try {
                favourites(page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 半截集合不能返回给调用方：setCollected/backfill 会把「不在集合里」
                // 当成「云端未收藏」并对翻转端点发请求，造成反向取消收藏。
                throw JmException("云端收藏列表加载失败：${e.message ?: "未知错误"}")
            }
            ids += r.items.map { it.id }
            if (r.page >= r.pages) break
            if (page >= FAV_MAX_PAGES) {
                throw JmException("云端收藏超过 ${FAV_MAX_PAGES * FAV_PAGE_SIZE} 本，无法安全对账")
            }
            page++
        }
        // 只有完整结果才进缓存；异常路径直接上抛，绝不以半截集合充当真相。
        favIdsCache = now to ids
        return ids
    }

    suspend fun isCloudFavourite(comicId: String): Boolean =
        isLoggedIn && cloudFavouriteIds().contains(comicId)

    /**
     * 收藏 / 取消收藏（翻转端点 POST /favorite {aid}）。
     * @return 调用后的收藏态（以服务端响应 type=add|remove 为准，不本地乐观）
     */
    suspend fun favourite(comicId: String): Boolean {
        val result = JmClient.favoriteToggle(comicId)
        // 翻转成功即作废缓存，下次拉列表重建
        favIdsCache = null
        favouriteVersion++
        return result?.isFavouriteAfter
            ?: throw JmException("收藏操作失败：${result?.msg ?: "服务端未返回结果"}")
    }

    /**
     * 月历签到数据（/daily?user_id=）。未登录或无 uid 返回 null（UI 不展示签到态）。
     */
    suspend fun dailyCalendar(): com.jmread.network.JmDailyCalendar? {
        if (!isLoggedIn) return null
        val uid = uidOrRelogin() ?: return null
        return runCatching { JmClient.dailyCalendar(uid).data }.getOrNull()
            .takeIf { it != null && it.dailyId.isNotBlank() }
    }

    /**
     * 云端能力统一取 uid。⚠ uid 落盘是 0.1.5 才加的——老版本登录后原地升级的用户 jmUid 为空，
     * 之前表现是签到恒报「签到数据不可用」。这里有保存的凭据就静默重登一次补齐，失败返回 null。
     */
    private suspend fun uidOrRelogin(): String? {
        SourcePrefs.current().jmUid?.let { return it }
        val cred = SecureAccountStore.load() ?: return null
        return runCatching {
            val data = JmClient.login(cred.first, cred.second)
            SourcePrefs.current().setJmLogin(data.s, cred.first, data.uid)
            data.uid
        }.getOrNull()
    }

    /**
     * 执行今日签到。返回到账文本（如 "Jcoin:40 EXP:40"）。
     * 今日已签（日历当天 signed=true）时直接返回提示，不发请求。
     */
    suspend fun checkIn(): String {
        val uid = uidOrRelogin() ?: throw JmException("登录态缺失，请重新登录")
        val cal = dailyCalendar() ?: throw JmException("签到数据不可用")
        val today = java.text.SimpleDateFormat("dd", java.util.Locale.US)
            .format(java.util.Date())
        // record 的 date 线上以零填充为主但偶发裸数字（date 走灵活字符串）。
        // 必须同时检查 signed：月历矩阵本来就会包含“今天”这一格（未签时 signed=false），
        // 只比日期会让每次签到都提前返回“今日已签到”。
        val already = isSignedOn(cal.record, today)
        if (already) return "今日已签到"
        val result = JmClient.dailyCheckIn(uid, cal.dailyId)
        return result?.msg ?: "签到完成"
    }

    /** 云端浏览历史（/watch_list，data 键 list） */
    suspend fun cloudHistory(page: Int): PageResult<ComicSummary> {
        val data = JmClient.watchList(page)
        val items = data.data.items.map { it.toSummary() }
        // /watch_list 与 /favorite 都是 20 条/页，不是浏览流的 80
        val pages = pagesOf(data.data.total, items.size, page, HISTORY_PAGE_SIZE, BROWSE_HARD_CAP)
        return PageResult(items.dedupeById(), page, pages, total = data.data.total)
    }

    // ---------- 频道（官方 App 首页内容位，2026-09-25 实测） ----------

    /**
     * 频道定义。全部基于 /search（c= 在 /categories/filter 上被服务端忽略，
     * 实测 2026-09-25；/search 上 c= 真实生效但需要关键词）。
     */
    data class ChannelDef(
        val id: String,
        val label: String,
        val keyword: String,
        val mainTag: Int,
        val description: String,
    )

    val channels: List<ChannelDef> = listOf(
        ChannelDef("hangroup", "禁漫汉化组", "禁漫汉化组", 0, "禁漫官方汉化组的最新译作"),
        ChannelDef("nomosaic", "禁漫去码", "禁漫去碼", 0, "官方去码修复版本"),
        ChannelDef("coloring", "全彩化", "全彩", 3, "全彩上色作品"),
    )

    fun channelOf(id: String): ChannelDef? = channels.firstOrNull { it.id == id }

    /** 频道内容流 = 按频道关键词的搜索分页 */
    suspend fun channelFeed(channelId: String, page: Int, sort: ComicSort = ComicSort.DD): PageResult<ComicSummary> {
        val def = channelOf(channelId) ?: throw JmException("未知频道：$channelId")
        return searchDimension(def.keyword, page, sort, mainTag = def.mainTag)
    }

    /**
     * 推荐本本（/promote 频道 block）。
     * ⚠ 内容会轮换、可能返回空 —— 调用方（UI）必须容空，不作为首屏承诺内容。
     */
    suspend fun promoteBlocks(page: Int = 1): List<PromoteBlock> {
        val resp = runCatching { JmClient.promote(page) }.getOrNull() ?: return emptyList()
        return resp.data.list.map { block ->
            PromoteBlock(
                id = block.id,
                title = block.title,
                // 服务端偶发重复条目：LazyRow 以 comic.id 作 key，重复会直接崩
                comics = block.content.distinctBy { it.id }.map { it.toSummary() },
            )
        }
    }

    // ---------- 内部 ----------

    private val PAGE_SIZE = 80
    /** /favorite 与 /watch_list 实测 20 条/页（2026-09-25 抓包：list[20]） */
    private val FAV_PAGE_SIZE = 20
    private val HISTORY_PAGE_SIZE = 20
    /** /forum 实测 10 条/页（fixture total=1075 / list=10） */
    private val COMMENT_PAGE_SIZE = 10
    /** 收藏对账最多翻 25 页（500 本），超过则明确报错而不是返回半截集合 */
    private const val FAV_MAX_PAGES = 25

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
        // 收藏列表项的更新章节信息（其他端点没有这两个键，恒为空）
        latestEpText = latestEpText,
        latestEpAid = latestEpAidText,
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
            isSpoiler = spoiler.isNotBlank() && spoiler != "0",
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

/** 推荐本本频道 block（/promote） */
data class PromoteBlock(
    val id: String,
    val title: String,
    val comics: List<ComicSummary>,
)
