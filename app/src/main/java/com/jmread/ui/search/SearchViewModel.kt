package com.jmread.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.PageResult
import com.jmread.core.model.sortedByComicSort
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 搜索维度（main_tag）：综合 / 作品 / 作者 / 标签，实测各有独立结果集与真实 total */
enum class SearchDimension(val mainTag: Int, val label: String) {
    SITE(0, "综合"),
    WORK(1, "作品"),
    AUTHOR(2, "作者"),
    TAG(3, "标签"),
}

/**
 * 搜索 VM：四维搜索（main_tag 0/1/2/3）+ 排序 + 官方标签入口。
 *
 * 多词（空格分隔）只在综合维度做交集（每词分别全文搜索取 id 交集，渐进展示）；
 * 作品/作者/标签维度为单关键词服务端搜索。
 */
class SearchViewModel : ViewModel() {

    /** 官方标签词表（/categories.blocks，标签维度搜索入口） */
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** 多词后台加载中（初始显示后，后台继续拉取剩余页时为 true） */
    private val _multiLoading = MutableStateFlow(false)
    val multiLoading: StateFlow<Boolean> = _multiLoading

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    /** 当前搜索维度 */
    private val _dimension = MutableStateFlow(SearchDimension.SITE)
    val dimension: StateFlow<SearchDimension> = _dimension

    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    /** 真实命中数（total），0 = 不可信/未返回 */
    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError

    private var searchJob: Job? = null

    /** 用于列表滚动位置恢复 */
    private var _savedFirstVisibleIndex: Int = 0
    val savedFirstVisibleIndex: Int get() = _savedFirstVisibleIndex

    var isScrollStateRestored: Boolean = false
        private set

    fun saveScrollState(firstVisibleIndex: Int) {
        _savedFirstVisibleIndex = firstVisibleIndex
    }

    fun markScrollStateRestored() {
        isScrollStateRestored = true
    }

    private val _shouldScrollToTop = MutableStateFlow(0)
    val shouldScrollToTop: StateFlow<Int> = _shouldScrollToTop

    private var multiSearchJob: Job? = null

    private val pageRetryCount = 2

    /** 多词搜索时每页展示数量 */
    private val pageSize = 20

    private var _multiAllComics: MutableList<ComicSummary> = mutableListOf()
    private var _confirmedIntersectionIds: Set<String> = emptySet()

    private val _multiSearchComplete = MutableStateFlow(false)
    val multiSearchComplete: StateFlow<Boolean> = _multiSearchComplete

    private var _activeSearchKey: String = ""

    /** 本界面实例是否已发起过搜索 */
    var hasSearched: Boolean = false
        private set

    /** 加载官方标签词表（失败静默：空列表时 UI 隐藏标签区） */
    fun loadTags() {
        if (_tags.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _tags.value = JmRepository.tags()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 词表失败忽略
            }
        }
    }

    /** 切换搜索维度（综合/作品/作者/标签），立即用当前关键词重搜 */
    fun setDimension(d: SearchDimension) {
        if (_dimension.value == d) return
        _dimension.value = d
        search(_keyword.value, page = 1)
    }

    fun updateFilter(sort: ComicSort = _sort.value) {
        _sort.value = sort
        search(_keyword.value, page = 1)
    }

    fun resetFilters() = updateFilter(sort = ComicSort.DD)

    /** 完全重置搜索状态 */
    fun resetAll() {
        searchJob?.cancel()
        multiSearchJob?.cancel()
        _activeSearchKey = ""
        _multiAllComics.clear()
        _confirmedIntersectionIds = emptySet()
        _multiSearchComplete.value = false
        _multiLoading.value = false
        _loading.value = false
        _searchError.value = null
        _dimension.value = SearchDimension.SITE
        resetFilters()
        _keyword.value = ""
        _comics.value = emptyList()
        _endReached.value = false
        _totalPages.value = 1
        _currentPage.value = 1
        _total.value = 0
    }

    /**
     * 点官方标签 = 标签维度搜索（main_tag=3）。
     * 比把标签当筛选叠加在关键词上诚实：本源无「关键词 AND 标签」参数。
     */
    fun searchTag(tag: String) {
        _dimension.value = SearchDimension.TAG
        search(tag, page = 1)
    }

    fun search(keyword: String, page: Int) {
        searchJob?.cancel()
        multiSearchJob?.cancel()
        hasSearched = true
        if (keyword.isBlank()) {
            _keyword.value = ""
            _searchError.value = null
            _activeSearchKey = ""
            _multiAllComics.clear()
            _confirmedIntersectionIds = emptySet()
            _multiSearchComplete.value = true
            _multiLoading.value = false
            _loading.value = false
            _comics.value = emptyList()
            _endReached.value = true
            _totalPages.value = 1
            _currentPage.value = 1
            _total.value = 0
            return
        }
        _comics.value = emptyList()
        _searchError.value = null
        _loading.value = true
        _multiLoading.value = false
        _endReached.value = false
        _keyword.value = keyword
        _currentPage.value = 1
        _multiAllComics.clear()
        _confirmedIntersectionIds = emptySet()
        _multiSearchComplete.value = false

        // 搜索键 = 关键词 + 维度 + 排序，用于过期的响应丢弃
        _activeSearchKey = "${_dimension.value.mainTag}|${_sort.value.name}|$keyword"

        multiSearchJob = viewModelScope.launch {
            try {
                val words = keyword.split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val multiWord = words.size > 1 && _dimension.value == SearchDimension.SITE
                if (!multiWord) {
                    // 单关键词（或非综合维度）：服务端分页
                    _multiSearchComplete.value = true
                    try {
                        val result = JmRepository.searchDimension(
                            keyword = keyword,
                            page = page,
                            sort = _sort.value,
                            mainTag = _dimension.value.mainTag,
                        )
                        if (_activeSearchKey != key()) return@launch
                        _comics.value = result.items
                        _totalPages.value = result.pages.coerceAtLeast(1)
                        _endReached.value = page >= result.pages
                        _currentPage.value = page
                        _total.value = result.total
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (_activeSearchKey == key()) {
                            _searchError.value = e.message?.takeIf { it.isNotBlank() } ?: "搜索失败，请稍后重试"
                            _endReached.value = true
                        }
                    }
                } else {
                    computeMultiWordIntersection(words, page)
                }
            } finally {
                if (isActive) _loading.value = false
            }
        }
    }

    private fun key(): String = "${_dimension.value.mainTag}|${_sort.value.name}|${_keyword.value}"

    /** 多词交集渐进式加载（仅综合维度） */
    private suspend fun computeMultiWordIntersection(
        words: List<String>,
        startPage: Int,
    ) {
        coroutineScope {
            val wordPageCounts = words.map { word ->
                val first = com.jmread.core.runCatchingCancellable {
                    searchWithRetry(word, startPage)
                }.getOrNull()
                word to (first?.pages ?: 1).coerceIn(1, 25)
            }

            val wordProgress = words.associateWith { startPage - 1 }.toMutableMap()
            val wordIdSets = mutableMapOf<String, MutableSet<String>>().apply {
                words.forEach { put(it, mutableSetOf()) }
            }

            // 阶段 1：拉每词起始页，建立初始交集
            for (word in words) {
                val page = (wordProgress[word] ?: 0) + 1
                val items = com.jmread.core.runCatchingCancellable {
                    searchWithRetry(word, page)
                }.getOrNull()?.items ?: emptyList()
                if (items.isNotEmpty()) {
                    wordIdSets[word]?.addAll(items.map { it.id })
                    _multiAllComics.addAll(items)
                    wordProgress[word] = page
                }
            }
            val intersection = computeIntersection(wordIdSets, words)
            _confirmedIntersectionIds = intersection
            if (_activeSearchKey == key()) {
                publishDisplay(intersection, complete = false)
            }

            // 阶段 2：继续拉取剩余页，逐步扩展交集
            var madeProgress = true
            while (madeProgress) {
                madeProgress = false
                val pendingWords = wordPageCounts.filter { (w, totalPages) ->
                    (wordProgress[w] ?: 0) < totalPages
                }
                if (pendingWords.isEmpty()) break
                madeProgress = true
                for ((w, _) in pendingWords) {
                    val nextPage = (wordProgress[w] ?: 0) + 1
                    val items = com.jmread.core.runCatchingCancellable {
                        searchWithRetry(w, nextPage)
                    }.getOrNull()?.items ?: emptyList()
                    if (items.isNotEmpty()) {
                        _multiAllComics.addAll(items)
                        wordIdSets[w]?.addAll(items.map { it.id })
                        wordProgress[w] = nextPage
                    }
                }
                val newIntersection = computeIntersection(wordIdSets, words)
                if (newIntersection != _confirmedIntersectionIds && _activeSearchKey == key()) {
                    _confirmedIntersectionIds = newIntersection
                    publishDisplay(newIntersection, complete = false)
                }
            }

            if (_activeSearchKey == key()) {
                publishFinalResult(_confirmedIntersectionIds)
            }
        }
    }

    /** 计算各词 id 集合的交集 */
    private fun computeIntersection(wordIdSets: Map<String, Set<String>>, words: List<String>): Set<String> {
        if (words.isEmpty()) return emptySet()
        var result = wordIdSets[words[0]] ?: emptySet()
        for (i in 1 until words.size) {
            result = result.intersect(wordIdSets[words[i]] ?: emptySet())
        }
        return result
    }

    private fun buildDisplayForPage(page: Int): List<ComicSummary> {
        return _multiAllComics
            .filter { it.id in _confirmedIntersectionIds }
            .distinctBy { it.id }
            .sortedByComicSort(_sort.value)
            .drop((page - 1) * pageSize)
            .take(pageSize)
    }

    /** 发布中间结果（够 1 页即显示），后台继续加载 */
    private fun publishDisplay(intersection: Set<String>, complete: Boolean) {
        val display = buildDisplayForPage(1)
        if (display.isEmpty()) return
        val newTotal = ((intersection.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        if (display == _comics.value && newTotal == _totalPages.value) return
        _comics.value = display
        _loading.value = false
        _multiLoading.value = !complete
        _endReached.value = complete
        _totalPages.value = newTotal
        _total.value = intersection.size
    }

    /** 发布最终结果，多词搜索完成 */
    private fun publishFinalResult(intersection: Set<String>) {
        _confirmedIntersectionIds = intersection
        _comics.value = buildDisplayForPage(1)
        _loading.value = false
        _multiLoading.value = false
        _totalPages.value = ((intersection.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        _endReached.value = _totalPages.value <= 1
        _multiSearchComplete.value = true
        _total.value = intersection.size
    }

    /** 排序切换（多词：本地重排；单词：服务端重搜） */
    fun updateSortOnly(sort: ComicSort) {
        _sort.value = sort
        val words = _keyword.value.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.size > 1 && _dimension.value == SearchDimension.SITE) {
            _currentPage.value = 1
            _comics.value = buildDisplayForPage(1)
            _endReached.value = _totalPages.value <= 1
        } else {
            search(_keyword.value, page = 1)
        }
    }

    /** 跳转到指定页（多词：客户端分页；单词：服务端分页） */
    fun jumpToPage(page: Int) {
        _shouldScrollToTop.value++
        val words = _keyword.value.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.size > 1 && _dimension.value == SearchDimension.SITE) {
            _currentPage.value = page
            _comics.value = buildDisplayForPage(page)
            _endReached.value = page >= _totalPages.value
        } else {
            search(_keyword.value, page)
        }
    }

    /** 加载更多（单词：服务端下一页累积；多词：客户端分页） */
    fun loadMore() {
        if (_loading.value) return
        val words = _keyword.value.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.size > 1 && _dimension.value == SearchDimension.SITE) {
            if (!_multiSearchComplete.value) return
            val nextPage = currentPage.value + 1
            val pageComics = buildDisplayForPage(nextPage)
            if (pageComics.isEmpty()) {
                _endReached.value = true
                return
            }
            _comics.value = _comics.value + pageComics
            _currentPage.value = nextPage
            _endReached.value = nextPage >= _totalPages.value
        } else {
            if (_endReached.value || _keyword.value.isBlank()) return
            val keyword = _keyword.value
            val next = _currentPage.value + 1
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                _loading.value = true
                try {
                    val result = JmRepository.searchDimension(
                        keyword = keyword,
                        page = next,
                        sort = _sort.value,
                        mainTag = _dimension.value.mainTag,
                    )
                    if (_activeSearchKey != key()) return@launch
                    _comics.value = _comics.value + result.items
                    _totalPages.value = result.pages.coerceAtLeast(1)
                    _currentPage.value = next
                    _endReached.value = next >= result.pages
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _endReached.value = true
                } finally {
                    if (isActive) _loading.value = false
                }
            }
        }
    }

    /** 切换筛选时重置页码显示 */
    fun resetFilterPage() {
        _currentPage.value = 1
    }

    /** 单页搜索，失败自动重试 */
    private suspend fun searchWithRetry(
        word: String,
        page: Int,
    ): PageResult<ComicSummary> {
        var last: Exception? = null
        repeat(pageRetryCount + 1) { attempt ->
            try {
                return JmRepository.searchDimension(word, page, _sort.value, mainTag = 0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < pageRetryCount) delay(500)
            }
        }
        throw last ?: RuntimeException("search failed")
    }
}
