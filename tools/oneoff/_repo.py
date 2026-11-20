# -*- coding: utf-8 -*-
"""JmRepository：云端收藏/翻转/历史/签到月历/频道封装"""
import io

p = 'app/src/main/java/com/jmread/core/JmRepository.kt'
s = io.open(p, encoding='utf-8').read()

# 1. login: store uid (hook + login)
old = '''    /** 由 JmApp.onCreate 调用：装配 401 静默重登钩子 */
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
    }'''
new = '''    /** 由 JmApp.onCreate 调用：装配 401 静默重登钩子 */
    fun init() {
        JmClient.onUnauthorizedHook = {
            val cred = SecureAccountStore.load()
            if (cred != null) {
                runCatching { JmClient.login(cred.first, cred.second) }
                    .onSuccess { SourcePrefs.current().setJmLogin(it.s, cred.first, it.uid) }
                    .onFailure { SourcePrefs.current().clearJmLogin() }
            } else {
                SourcePrefs.current().clearJmLogin()
            }
        }
    }

    suspend fun login(account: String, password: String) {
        val data = JmClient.login(account, password)
        SourcePrefs.current().setJmLogin(data.s, account.trim(), data.uid)
        SecureAccountStore.save(account.trim(), password)
    }'''
assert old in s, "login block"
s = s.replace(old, new)

# 2. favIdsCache field
old = '''    /** 榜单档位探测结果缓存：(探测时刻, 可用档位) */
    @Volatile private var rankProbeCache: Pair<Long, List<RankTab>>? = null'''
new = '''    /** 榜单档位探测结果缓存：(探测时刻, 可用档位) */
    @Volatile private var rankProbeCache: Pair<Long, List<RankTab>>? = null

    /** 云端收藏 id 集合缓存：(探测时刻, ids)；收藏开关成功后置 null 作废 */
    @Volatile private var favIdsCache: Pair<Long, Set<String>>? = null
    private const val FAV_IDS_TTL_MS = 5 * 60 * 1000L'''
assert old in s, "cache field"
s = s.replace(old, new)

# 3. auth section rewrite
old = '''    // ---------- 需登录（能力位未开，验证后启用） ----------

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
    }'''
new = '''    // ---------- 需登录（2026-09-25 实测定案，能力位已开） ----------

    /**
     * 云端收藏列表。排序 orderBy："mr" 收藏时间（默认）/"mp" 更新时间。
     * 列表项自带 latest_ep —— 「有更新」角标的数据源。
     */
    suspend fun favourites(page: Int, orderBy: String = "mr"): PageResult<ComicSummary> {
        val data = JmClient.favorites(page, orderBy)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, FAV_PAGE_SIZE, 500)
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
        // 拉前 3 页（240 本）覆盖绝大多数用户的收藏量；更多时以收藏列表页为准
        for (page in 1..3) {
            val r = runCatching { favourites(page) }.getOrNull() ?: break
            ids += r.items.map { it.id }
            if (r.items.size < FAV_PAGE_SIZE) break
        }
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
        return result?.isFavouriteAfter
            ?: throw JmException("收藏操作失败：${result?.msg ?: "服务端未返回结果"}")
    }

    /**
     * 月历签到数据（/daily?user_id=）。未登录或无 uid 返回 null（UI 整卡隐藏）。
     */
    suspend fun dailyCalendar(): com.jmread.network.JmDailyCalendar? {
        if (!isLoggedIn) return null
        val uid = SourcePrefs.current().jmUid ?: return null
        return runCatching { JmClient.dailyCalendar(uid).data }.getOrNull()
            .takeIf { it != null && it.dailyId.isNotBlank() }
    }

    /**
     * 执行今日签到。返回到账文本（如 "Jcoin:40 EXP:100"）。
     * 今日已签（日历当天 signed=true）时直接返回提示，不发请求。
     */
    suspend fun checkIn(): String {
        val cal = dailyCalendar() ?: throw JmException("签到数据不可用")
        val today = java.text.SimpleDateFormat("dd", java.util.Locale.US)
            .format(java.util.Date()).removePrefix("0")
        val already = cal.record.any { row -> row.any { it.date == today && it.signed } }
        if (already) return "今日已签到"
        val result = JmClient.dailyCheckIn(uid(), cal.dailyId)
        return result?.msg ?: "签到完成"
    }

    private fun uid(): String = SourcePrefs.current().jmUid ?: ""

    /** 云端浏览历史（/watch_list，data 键 list） */
    suspend fun cloudHistory(page: Int): PageResult<ComicSummary> {
        val data = JmClient.watchList(page)
        val items = data.data.items.map { it.toSummary() }
        val pages = pagesOf(data.data.total, items.size, page, PAGE_SIZE, BROWSE_HARD_CAP)
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
    suspend fun promoteBlocks(): List<PromoteBlock> {
        val resp = runCatching { JmClient.promote() }.getOrNull() ?: return emptyList()
        return resp.data.list.map { block ->
            PromoteBlock(
                id = block.id,
                title = block.title,
                comics = block.content.map { it.toSummary() },
            )
        }
    }'''
assert old in s, "auth section"
s = s.replace(old, new)

# 4. toSummary: latest fields
old = '''    private fun com.jmread.network.JmAlbumSummary.toSummary() = ComicSummary(
        id = id,
        title = name,
        author = author,
        coverUrl = JmCrypto.coverUrl(id),
        isFavourite = isFavorite,
        updatedAt = displayUpdatedAt(updateAt, adddate),
        // 原始 update_at 单独保留，供「新晋热榜」按最近更新排序
        lastUpdatedAt = updateAt,
        categoryName = category.titleText,
    )'''
new = '''    private fun com.jmread.network.JmAlbumSummary.toSummary() = ComicSummary(
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
    )'''
assert old in s, "toSummary"
s = s.replace(old, new)

# 5. PromoteBlock model at file tail
old = '''data class JmSubCategoryNode(val slug: String, val name: String)

data class TagGroup(val title: String, val tags: List<String>)'''
new = '''data class JmSubCategoryNode(val slug: String, val name: String)

data class TagGroup(val title: String, val tags: List<String>)

/** 推荐本本频道 block（/promote） */
data class PromoteBlock(
    val id: String,
    val title: String,
    val comics: List<ComicSummary>,
)'''
assert old in s, "tail models"
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(s)
print('done')
