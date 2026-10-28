package com.jmread.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.RankTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            val tabs = runCatching { JmRepository.availableRankTabs() }
                .getOrDefault(RankTab.optimistic)
            if (tabs.isEmpty()) return@launch
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

    /** 排行榜请求代际：快速切榜时旧响应不得覆盖新选择 */
    private var rankGeneration = 0

    fun loadRank(type: String, force: Boolean = false, startDelayMs: Long = 0) {
        if (!force && _rankType.value == type && _rankComics.value.isNotEmpty() && _rankError.value == null) return
        val gen = ++rankGeneration
        _rankType.value = type
        if (!force && _rankComics.value.isNotEmpty()) _rankComics.value = emptyList()
        _rankLoading.value = true
        _rankError.value = null
        viewModelScope.launch {
            try {
                if (startDelayMs > 0) kotlinx.coroutines.delay(startDelayMs)
                val result = JmRepository.rank(type)
                if (gen != rankGeneration) return@launch
                _rankComics.value = result.items
                _rankTotal.value = result.total
                rankLoadedAt = System.currentTimeMillis()
                _rankRefreshTick.value++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != rankGeneration) return@launch
                if (!force) _rankComics.value = emptyList()
                _rankError.value = e.message ?: "加载排行榜失败"
            } finally {
                if (gen == rankGeneration) _rankLoading.value = false
            }
        }
    }
}
