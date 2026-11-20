# -*- coding: utf-8 -*-
"""HomeViewModel 精简为纯排行榜 VM；关注流/每周必看逻辑拆出（由各自新 VM 承接）"""
import io

p = 'app/src/main/java/com/jmread/ui/home/HomeViewModel.kt'
s = io.open(p, encoding='utf-8').read()

# 1. header comment
old = '''/**
 * 首页数据聚合：排行榜(H24/D7/D30) + 关注信息流 + 每周必看。
 * 关注流 = 所有关注来源（收藏作者/关键词/分类标签）的最新作品合并，
 * 按更新时间由近至远排序、按 id 去重，滚动加载（每个来源逐页拉取）。
 */
class HomeViewModel : ViewModel() {'''
new = '''/**
 * 首页数据：排行榜主内容（档位由探测给出，见 [RankTab]）。
 *
 * 2026-09-25 设计变更：首页直接以排行榜为主体（官方 App 对齐），
 * 关注流与每周必看拆分为独立页面（FollowFeedScreen / WeeklyScreen），
 * 频道（推荐本本/汉化组/去码/全彩化）由频道 chips 进入（ChannelScreen / PromoteScreen）。
 */
class HomeViewModel : ViewModel() {'''
assert old in s, "doc"
s = s.replace(old, new)

# 2. cut follow & week state/logic blocks
start = s.index('    private val _followFeed = MutableStateFlow<List<ComicSummary>>(emptyList())')
end = s.index('    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())')
s = s[:start] + s[end:]

# 3. remove follow-refresh plumbing inside refreshOnResume
old = '''    fun refreshOnResume() {
        val now = System.currentTimeMillis()
        if (rankLoadedAt > 0 && now - rankLoadedAt >= RANK_REFRESH_TTL_MS) {
            loadRank(_rankType.value, force = true, startDelayMs = 2_000)
        }
        if (now - lastAutoRefreshAt < 30_000) return
        everRefreshed = true
        refresh()
    }'''
new = '''    fun refreshOnResume() {
        val now = System.currentTimeMillis()
        if (rankLoadedAt > 0 && now - rankLoadedAt >= RANK_REFRESH_TTL_MS) {
            loadRank(_rankType.value, force = true, startDelayMs = 2_000)
        }
    }'''
assert old in s, "refreshOnResume"
s = s.replace(old, new)

# 4. remove initialRefresh + lastAutoRefreshAt/everRefreshed + follow helpers + week + targets + scroll-for-3tabs
start = s.index('    private var lastAutoRefreshAt = 0L')
end = s.index('    private val _rankLoading = MutableStateFlow(false)')
s = s[:start] + s[end:]

# 5. cut week + follow blocks after rank section
start = s.index('    // ---- 每周必看 ----')
end = s.index('    /** 各关注来源当前已加载到的页数（key -> page） */')
s = s[:start] + s[end:]
start = s.index('    /** 各关注来源当前已加载到的页数（key -> page） */')
end = s.index('    /** 各 Tab 滚动位置恢复 */')
s = s[:start] + s[end:]

# 6. scroll state: only rank
old = '''    /** 各 Tab 滚动位置恢复 */
    private var _savedFollowIndex = 0
    val savedFollowIndex: Int get() = _savedFollowIndex

    private var _savedRankIndex = 0
    val savedRankIndex: Int get() = _savedRankIndex

    private var _savedWeekIndex = 0
    val savedWeekIndex: Int get() = _savedWeekIndex

    private val _isScrollStateRestored = MutableStateFlow(false)
    val isScrollStateRestored: StateFlow<Boolean> = _isScrollStateRestored

    fun saveScrollState(tab: Int, index: Int) {
        when (tab) {
            0 -> _savedFollowIndex = index
            1 -> _savedRankIndex = index
            2 -> _savedWeekIndex = index
        }
    }

    fun markScrollStateRestored() {
        _isScrollStateRestored.value = true
    }

    init {
        _followFeed.value = com.jmread.data.FollowFeedCache.load()
            .map { fillUpdatedAt(it) }
        viewModelScope.launch {
            com.jmread.data.UpdatedAtCache.version.collect {
                val current = _followFeed.value
                val refilled = current.map { fillUpdatedAt(it) }
                if (refilled != current) _followFeed.value = refilled
            }
        }
    }'''
new = '''    /** 排行榜滚动位置恢复 */
    private var _savedRankIndex = 0
    val savedRankIndex: Int get() = _savedRankIndex

    private val _isScrollStateRestored = MutableStateFlow(false)
    val isScrollStateRestored: StateFlow<Boolean> = _isScrollStateRestored

    fun saveScrollState(index: Int) {
        _savedRankIndex = index
    }

    fun markScrollStateRestored() {
        _isScrollStateRestored.value = true
    }'''
assert old in s, "scroll"
s = s.replace(old, new)

# 7. cut ensureFollowTargets..refresh() and week functions and follow fetch helpers
start = s.index('    /** 首次进入关注 tab：构建关注来源列表 */')
end = s.index('    /** 排行榜请求代际：快速切榜时旧响应不得覆盖新选择 */')
s = s[:start] + s[end:]
# now remove the week/follow helpers after loadRank (from ensureWeekLoaded to end of searchWithRetry/fillUpdatedAt)
start = s.index('    /** 首次进入每周必看 tab：加载期数列表并选中最新一期 */')
end = s.rindex('}')  # class closing brace
# find the tail: everything from ensureWeekLoaded to class end minus closing brace
s = s[:start] + '}\n'
# drop trailing extra brace if doubled
s = s.replace('}\n}\n', '}\n') if s.count('}') and s.rstrip().endswith('}\n}') else s

io.open(p, 'w', encoding='utf-8').write(s)
print('done')
