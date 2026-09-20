package com.jmread.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.model.ComicCategory
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicStatus
import com.jmread.core.model.ComicSummary
import com.jmread.core.JmRepository
import com.jmread.core.JmCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 首页浏览 VM：分类 + 内容流（分页）。
 * 走 SourceManager 当前源，源切换后自动重载。
 * 支持排序（哔咔服务端 / 禁漫客户端重排）、连载状态筛选与更新日期范围筛选
 * （均为客户端过滤 + 自动补页）。
 *
 * 缓存优化：rawItems 在切换排序/状态/日期范围时保留，避免重新请求网络。
 * 仅在切换分类或源时清空重载。
 */
class BrowseViewModel : ViewModel() {

    private val _categories = MutableStateFlow<List<ComicCategory>>(emptyList())
    val categories: StateFlow<List<ComicCategory>> = _categories

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _status = MutableStateFlow(ComicStatus.ALL)
    val status: StateFlow<ComicStatus> = _status

    private val _author = MutableStateFlow<String?>(null)
    val author: StateFlow<String?> = _author

    private val _tag = MutableStateFlow<String?>(null)
    val tag: StateFlow<String?> = _tag

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    /** 已拉取的原始数据（未过滤/未重排），供筛选与排序使用 */
    private val rawItems = mutableListOf<ComicSummary>()

    private var category: String? = null

    /** 已加载数据的标识（源+分类，由界面传入）：从详情返回重组时相同且有数据则跳过重载，保留累积分页与滚动状态 */
    private var loadedKey: String? = null

    /** 加载代际：切排序/筛选/换源时递增，使旧加载在 await 后失效，避免并发写脏数据 */
    private var loadToken = 0

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    /** 用于列表滚动位置恢复 */
    private var _savedFirstVisibleIndex: Int = 0
    val savedFirstVisibleIndex: Int get() = _savedFirstVisibleIndex

    /** 是否已恢复过滚动位置 */
    var isScrollStateRestored: Boolean = false
        private set

    fun saveScrollState(firstVisibleIndex: Int) {
        _savedFirstVisibleIndex = firstVisibleIndex
    }

    fun markScrollStateRestored() {
        isScrollStateRestored = true
    }

    fun loadCategories() {
        viewModelScope.launch {
            try {
                _categories.value = JmRepository.categories()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 分类失败不阻塞浏览
            }
        }
    }

    fun loadComics(page: Int, category: String? = null, reloadKey: String? = null) {
        if (page <= 1 && reloadKey != null) {
            // 同一分类同一源且已有数据：界面重组（如从详情返回）触发时跳过重载；
            // 内部 reload（切排序等）不带 key，不覆盖 loadedKey，返回后仍可跳过重载
            if (reloadKey == loadedKey && rawItems.isNotEmpty()) return
            this.category = category
            loadedKey = reloadKey
        }
        jumpToPage(page)
    }

    /** 跳转到指定页（严格单页） */
    fun jumpToPage(page: Int) {
        loadPage(page, append = false)
    }

    /** 累积加载下一页（筛选激活时后台拉全部分页用）：追加到 rawItems 并重新过滤排序 */
    fun loadMore() {
        if (_loading.value || _endReached.value) return
        loadPage(_currentPage.value + 1, append = true)
    }

    /** 切换筛选时重置页码显示（累积数据仍在，列表从头展示） */
    fun resetFilterPage() {
        _currentPage.value = 1
    }

    private fun loadPage(page: Int, append: Boolean) {
        val token = ++loadToken
        _endReached.value = true  // 防止加载期间 ComicGridView 触发 recompose
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                if (!append) rawItems.clear()
                if (token != loadToken) return@launch
                val p = page.coerceAtLeast(1)
                val result = JmRepository.browse(
                    page = p,
                    category = this@BrowseViewModel.category,
                    sort = _sort.value,
                )
                if (token != loadToken) return@launch
                rawItems += result.items
                _currentPage.value = p
                _totalPages.value = result.pages.coerceAtLeast(1)
                _endReached.value = p >= result.pages
                applyFilterAndSort()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新一次跳页取代：放行，且不得污染 endReached/error
                throw e
            } catch (e: Exception) {
                if (token == loadToken) {
                    // 失败要解锁 endReached，否则 loadMore 被永久拦截，分页失效
                    _endReached.value = false
                    _error.value = e.message ?: "加载失败"
                }
            } finally {
                if (isActive && token == loadToken) _loading.value = false
            }
        }
    }

    fun setSort(sort: ComicSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        reload()
    }

    fun setStatus(status: ComicStatus) {
        // 本源无完结字段，能力位为 false：状态筛选不生效（UI 不出现入口）
        if (status != ComicStatus.ALL && !JmCapabilities.supportsStatusFilter) return
        if (_status.value == status) return
        _status.value = status
        if (rawItems.isNotEmpty()) {
            applyFilterAndSort()
            return
        }
        reload()
    }

    fun setAuthor(author: String?) {
        if (_author.value == author) return
        _author.value = author
        reload()
    }

    fun setTag(tag: String?) {
        if (_tag.value == tag) return
        _tag.value = tag
        reload()
    }

    private fun reload() {
        rawItems.clear()
        _comics.value = emptyList()
        _error.value = null
        _endReached.value = false
        _currentPage.value = 1
        loadComics(page = 1, category = category)
    }

    /** 状态筛选（客户端）→ 排序（禁漫客户端重排；哔咔按 updatedAt 字符串重排以支持 DA/DD 切换） */
    private fun applyFilterAndSort() {
        val filtered = rawItems.filter {
            when (_status.value) {
                ComicStatus.ALL -> true
                ComicStatus.FINISHED -> it.finished
                ComicStatus.ONGOING -> !it.finished
            }
        }
        val sorted = when (_sort.value) {
            ComicSort.DD -> filtered.sortedByDescending { it.updatedAt }
            ComicSort.DA -> filtered.sortedBy { it.updatedAt }
            ComicSort.LD -> filtered.sortedByDescending { it.totalLikes }
            ComicSort.VD -> filtered.sortedByDescending { it.totalViews }
        }
        _comics.value = sorted
    }
}
