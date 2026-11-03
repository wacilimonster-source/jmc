package com.jmread.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.JmRepository
import com.jmread.core.PromoteBlock
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.RankTab
import com.jmread.data.RankCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 排行榜下方展开的内容分区（/promote 的频道 block） */
data class HomePromoteSection(
    val key: String,
    val title: String,
    val comics: List<ComicSummary>,
)

/** 每周必看最新一期（只取第一页；完整期数切换走独立页） */
data class HomeWeeklySection(
    val label: String,
    val comics: List<ComicSummary>,
)

/**
 * 首页展开分区里不需要的 promote block（服务端「连载更新」运营位）。
 * 接口按语言返回简/繁两种标题写法（实机为繁体「連載更新」），两种都要匹配。
 */
private val HIDDEN_PROMOTE_BLOCK_KEYWORDS = listOf("连载更新", "連載更新")

/**
 * /promote block → 首页展开分区。
 *
 * 空 block 不占位；「连载更新」运营位按产品要求隐藏；
 * 线上标题带「→右滑看更多→」这类排版提示，展开成网格后无意义，截断保留主标题。
 */
internal fun homePromoteSections(blocks: List<PromoteBlock>): List<HomePromoteSection> =
    blocks
        .filter { block ->
            block.comics.isNotEmpty() &&
                HIDDEN_PROMOTE_BLOCK_KEYWORDS.none { block.title.contains(it) }
        }
        .map { block ->
            HomePromoteSection(
                key = block.id,
                title = block.title.substringBefore("→").trim().ifBlank { "推荐" },
                comics = block.comics,
            )
        }

/** 排行榜数据过期阈值：回前台时超过该时长则静默重拉 */
private const val RANK_REFRESH_TTL_MS = 10 * 60 * 1000L

/**
 * 首页数据：排行榜主内容（档位由探测给出，见 [RankTab]）。
 *
 * 2026-09-25 设计变更：首页直接以排行榜为主体（官方 App 对齐），
 * 关注流与每周必看拆分为独立页面（FollowFeedScreen / WeeklyScreen），
 * 频道（推荐本本/汉化组/去码/全彩化）由频道 chips 进入（ChannelScreen / PromoteScreen）。
 */
class HomeViewModel : ViewModel() {

    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val rankComics: StateFlow<List<ComicSummary>> = _rankComics.asStateFlow()

    /** 当前选中的榜单档位，默认新晋热榜（月榜热度池本地重排，永不为空） */
    private val _rankType = MutableStateFlow(RankTab.H24.value)
    val rankType: StateFlow<String> = _rankType.asStateFlow()

    /**
     * 当前可用的榜单档位。
     * 初值是「乐观全集」：先按全集渲染，避免等探测回来才出 tab（首屏空白）。
     * 探测完成后收敛到线上真有数据的档位；若正选中的档位被判定不可用，自动切到首个可用档位。
     */
    private val _rankTabs = MutableStateFlow(RankTab.optimistic)
    val rankTabs: StateFlow<List<RankTab>> = _rankTabs.asStateFlow()

    private var rankTabsProbed = false

    /** 探测榜单档位可用性。失败时保持乐观全集：宁可多一个空档位，也不藏掉能用的档位。 */
    fun ensureRankTabs() {
        if (rankTabsProbed) return
        rankTabsProbed = true
        viewModelScope.launch {
            val probed = runCatching { JmRepository.availableRankTabs() }.getOrNull()
            val tabs = probed?.takeIf { it.isNotEmpty() }
                ?: withContext(Dispatchers.IO) { RankCache.loadTabs() }
                ?: RankTab.optimistic
            if (probed != null && probed.isNotEmpty()) {
                withContext(Dispatchers.IO) { RankCache.saveTabs(probed) }
            }
            _rankTabs.value = tabs
            if (tabs.none { it.value == _rankType.value }) {
                loadRank(tabs.first().value, force = true)
            }
        }
    }

    /** 首屏默认 true：在首次请求真正开始前先显示骨架，避免闪一帧「暂无数据」 */
    private val _rankLoading = MutableStateFlow(true)
    val rankLoading: StateFlow<Boolean> = _rankLoading.asStateFlow()

    private val _rankError = MutableStateFlow<String?>(null)
    val rankError: StateFlow<String?> = _rankError.asStateFlow()

    /** 非空 = 当前展示的是缓存，值为缓存时间戳（P1-07 横幅用） */
    private val _rankCacheAt = MutableStateFlow<Long?>(null)
    val rankCacheAt: StateFlow<Long?> = _rankCacheAt.asStateFlow()

    private var rankLoadedAt = 0L

    private val _rankRefreshTick = MutableStateFlow(0)
    val rankRefreshTick: StateFlow<Int> = _rankRefreshTick.asStateFlow()

    /** 排行榜真实命中数（总排行/月榜为哨兵值 10000 时不展示，见 UI） */
    private val _rankTotal = MutableStateFlow(0)
    val rankTotal: StateFlow<Int> = _rankTotal.asStateFlow()

    /** 排行榜滚动位置恢复 */
    private var _savedRankIndex = 0
    val savedRankIndex: Int get() = _savedRankIndex

    private val _isScrollStateRestored = MutableStateFlow(false)
    val isScrollStateRestored: StateFlow<Boolean> = _isScrollStateRestored

    // ── 随便看看：本源无 /random 端点，从月榜热度池本地随机 ──────────────

    private val _randomComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val randomComics: StateFlow<List<ComicSummary>> = _randomComics.asStateFlow()

    private val _randomLoading = MutableStateFlow(false)
    val randomLoading: StateFlow<Boolean> = _randomLoading.asStateFlow()

    private val _randomError = MutableStateFlow<String?>(null)
    val randomError: StateFlow<String?> = _randomError.asStateFlow()

    private var randomLoaded = false
    private var randomJob: kotlinx.coroutines.Job? = null

    fun ensureRandomLoaded() {
        if (randomLoaded) return
        refreshRandom()
    }

    /** 从月榜前两页热池本地洗牌；失败保留旧列表并给出可见错误。 */
    fun refreshRandom() {
        randomLoaded = true
        if (_randomLoading.value) return
        _randomLoading.value = true
        _randomError.value = null
        randomJob?.cancel()
        randomJob = viewModelScope.launch {
            try {
                val pool = buildList {
                    for (page in 1..2) {
                        val part = runCatching { JmRepository.rank("mv_m", page) }
                            .getOrNull()?.items.orEmpty()
                        addAll(part)
                    }
                }.distinctBy { it.id }
                if (pool.isEmpty()) {
                    // 月榜整体不可用时退到周榜，避免「随便看看」直接空白
                    val week = runCatching { JmRepository.rank("mv_w", 1) }
                        .getOrNull()?.items.orEmpty().distinctBy { it.id }
                    if (week.isEmpty()) error("热门池加载失败，请稍后重试")
                    _randomComics.value = week.shuffled().take(80)
                } else {
                    _randomComics.value = pool.shuffled().take(80)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_randomComics.value.isEmpty()) _randomComics.value = emptyList()
                _randomError.value = e.message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试"
            } finally {
                _randomLoading.value = false
            }
        }
    }

    // ── 排行榜下方展开分区：/promote 频道 block + 每周必看最新一期 ──────────
    // 首页不再用 chips 逐页跳转，而是把内容直接铺在榜单下方；
    // 「连载更新」运营位按产品要求隐藏，其余 block 原样展开。

    private val _promoteSections = MutableStateFlow<List<HomePromoteSection>>(emptyList())
    val promoteSections: StateFlow<List<HomePromoteSection>> = _promoteSections.asStateFlow()

    private val _weeklySection = MutableStateFlow<HomeWeeklySection?>(null)
    val weeklySection: StateFlow<HomeWeeklySection?> = _weeklySection.asStateFlow()

    /** 分区刷新指示（分区与榜单档位同级切换，下拉刷新时各自显示进度） */
    private val _sectionsLoading = MutableStateFlow(false)
    val sectionsLoading: StateFlow<Boolean> = _sectionsLoading.asStateFlow()

    private var promoteJob: Job? = null
    private var weeklyJob: Job? = null

    fun ensureSectionsLoaded() {
        if (_promoteSections.value.isEmpty()) refreshPromoteSections()
        if (_weeklySection.value == null) refreshWeeklySection()
    }

    fun refreshSections() {
        refreshPromoteSections()
        refreshWeeklySection()
    }

    /** /promote 内容会轮换且可能为空：失败保留旧分区，不阻塞榜单 */
    fun refreshPromoteSections() {
        if (promoteJob?.isActive == true) return
        _sectionsLoading.value = true
        promoteJob = viewModelScope.launch {
            try {
                val blocks = runCatching { JmRepository.promoteBlocks() }.getOrDefault(emptyList())
                if (blocks.isEmpty()) return@launch
                _promoteSections.value = homePromoteSections(blocks)
            } finally {
                _sectionsLoading.value = false
            }
        }
    }

    /** 每周必看只取最新一期第一页；期数切换仍由独立页承接 */
    fun refreshWeeklySection() {
        if (weeklyJob?.isActive == true) return
        weeklyJob = viewModelScope.launch {
            val section = runCatching {
                val period = JmRepository.weeklyPeriods().firstOrNull()
                    ?: return@runCatching null
                val content = JmRepository.weeklyContent(period.id, 1)
                HomeWeeklySection(
                    label = period.label.ifBlank { "第 ${period.id} 期" },
                    comics = content.items,
                )
            }.getOrNull()
            if (section != null) {
                _weeklySection.value = section
            } else if (_weeklySection.value == null) {
                // 内容轮换/网络抖动不能让「每周必看」入口整个消失；保留入口，
                // 独立页内有错误态与重试。
                _weeklySection.value = HomeWeeklySection(label = "每周必看", comics = emptyList())
            }
        }
    }

    fun saveScrollState(index: Int) {
        _savedRankIndex = index
    }

    fun markScrollStateRestored() {
        _isScrollStateRestored.value = true
    }

    /** 回前台：榜单数据过期则静默重拉（保留旧数据展示，成功后替换并回顶） */
    fun refreshOnResume() {
        val now = System.currentTimeMillis()
        if (rankLoadedAt > 0 && now - rankLoadedAt >= RANK_REFRESH_TTL_MS) {
            loadRank(_rankType.value, force = true, startDelayMs = 2_000)
        }
    }

    /** 首屏内容已可见（缓存或网络/失败终态）：分区与档位探测在此之后低优先级触发 */
    private val _rankFirstContentReady = MutableStateFlow(false)
    val rankFirstContentReady: StateFlow<Boolean> = _rankFirstContentReady.asStateFlow()
    private var rankContentReady = false

    private fun markRankContentReady() {
        if (rankContentReady) return
        rankContentReady = true
        _rankFirstContentReady.value = true
    }

    /**
     * 首屏榜单已可见后再加载次级内容（PERF-05）：
     * 档位能力探测会额外打 3 个请求，分区/每周必看又各打 1~2 个，
     * 与首屏榜单同帧启动会直接争抢带宽与图片预算。
     */
    fun onRankContentVisible() {
        ensureRankTabs()
        ensureSectionsLoaded()
    }

    /** 排行榜请求代际：快速切榜时旧响应不得覆盖新选择 */
    private var rankGeneration = 0

    /** 当前榜单加载任务：切榜/刷新时取消上一 job，旧请求不再占用连接 */
    private var rankJob: Job? = null

    /**
     * 加载榜单（PERF-05）：
     *  1. 先读该档位的磁盘缓存并在代际仍有效时发布，有缓存就不挡首屏；
     *  2. 网络成功后再覆盖，空结果同样覆盖旧缓存（避免旧榜单假装仍有效）；
     *  3. 网络失败时保留缓存并给出可重试提示；
     *  4. 切榜取消上一 job，JSON 解码与缓存读写全部在 IO。
     */
    fun loadRank(type: String, force: Boolean = false, startDelayMs: Long = 0) {
        if (!force && _rankType.value == type && _rankComics.value.isNotEmpty() && _rankError.value == null) return
        val gen = ++rankGeneration
        val typeChanged = _rankType.value != type
        _rankType.value = type
        rankJob?.cancel()
        // 切档位立刻清空旧内容，避免新档位标题下展示上一档的榜单；
        // 同档位强制刷新则保留当前内容（缓存先展示）。
        if (typeChanged) _rankComics.value = emptyList()
        _rankLoading.value = true
        _rankError.value = null
        _rankCacheAt.value = null
        rankJob = viewModelScope.launch {
            try {
                if (startDelayMs > 0) kotlinx.coroutines.delay(startDelayMs)

                // 1) 缓存先展示：读盘/JSON 解码在 IO，代际校验后才发布
                val cached = withContext(Dispatchers.IO) { RankCache.load(type) }
                if (gen != rankGeneration) return@launch
                if (cached != null) {
                    _rankComics.value = cached.items
                    _rankTotal.value = cached.total
                    rankLoadedAt = cached.at
                    // 刷新进行中不显示「服务端异常」横幅；失败时才标记缓存时间。
                    // 空缓存不算「内容可见」，继续等网络终态再加载次级入口。
                    if (cached.items.isNotEmpty()) markRankContentReady()
                }

                // 2) 网络刷新
                val result = JmRepository.rank(type)
                if (gen != rankGeneration) return@launch
                _rankComics.value = result.items
                _rankTotal.value = result.total
                _rankCacheAt.value = null
                _rankError.value = null
                markRankContentReady()
                withContext(Dispatchers.IO) { RankCache.save(type, result.items, result.total) }
                rankLoadedAt = System.currentTimeMillis()
                _rankRefreshTick.value++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != rankGeneration) return@launch
                // 失败：保留缓存（若首屏已发布则内容仍在），并标记缓存时间供横幅展示
                val cached = withContext(Dispatchers.IO) { RankCache.load(type) }
                if (gen != rankGeneration) return@launch
                if (cached != null) {
                    _rankComics.value = cached.items
                    _rankTotal.value = cached.total
                    _rankCacheAt.value = cached.at
                    _rankError.value = null
                    // 缓存也有时间戳：否则 rankLoadedAt 恒 0，refreshOnResume 永远不会重试
                    rankLoadedAt = cached.at
                } else {
                    _rankCacheAt.value = null
                    _rankError.value = e.message ?: "加载排行榜失败"
                }
                // 无缓存且网络失败：首屏已进入错误终态，次级入口仍要可用
                markRankContentReady()
            } finally {
                if (gen == rankGeneration) _rankLoading.value = false
            }
        }
    }
}
