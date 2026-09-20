package com.jmread.ui.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.model.ComicSort
import com.jmread.core.model.ComicStatus
import com.jmread.core.model.ComicSummary
import com.jmread.core.JmRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AuthorViewModel : ViewModel() {

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

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    private var _author: String = ""

    /** 用于列表滚动位置恢复 */
    private var _savedFirstVisibleIndex: Int = 0
    val savedFirstVisibleIndex: Int get() = _savedFirstVisibleIndex

    private var _savedCurrentPage: Int = 1
    val savedCurrentPage: Int get() = _savedCurrentPage

    /** 是否需要恢复滚动位置 */
    private var _needsScrollRestore: Boolean = false
    val needsScrollRestore: Boolean get() = _needsScrollRestore

    private var loadJob: Job? = null

    fun loadComics(author: String, page: Int) {
        _author = author
        loadJob?.cancel()
        _loading.value = true
        _error.value = null
        _endReached.value = false
        _currentPage.value = page

        loadJob = viewModelScope.launch {
            try {
                // 作者维度搜索（main_tag=2，实测独立结果集与真实 total）
                val result = JmRepository.searchDimension(
                    keyword = author,
                    page = page,
                    sort = _sort.value,
                    mainTag = 2,
                )
                _comics.value = result.items
                _totalPages.value = result.pages.coerceAtLeast(1)
                _endReached.value = page >= result.pages
                applyFilterAndSort()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "加载失败"
            } finally {
                if (isActive) _loading.value = false
            }
        }
    }

    /**
     * 返回页面的滚动恢复专用：只回填页码指示（Navigation 已自动恢复 LazyGridState），
     * 不吞掉任何加载请求。此前恢复分支内嵌在 loadComics 里，切后台回来后的第一次
     * setSort/setStatus 会被误当成恢复、只回填页码不发请求，表现为"点了没反应"。
     */
    fun consumeScrollRestorePage(): Int? {
        if (!_needsScrollRestore) return null
        _needsScrollRestore = false
        _currentPage.value = _savedCurrentPage
        return _savedCurrentPage
    }

    fun jumpToPage(page: Int) {
        _needsScrollRestore = false
        _endReached.value = true
        loadComics(_author, page)
    }

    fun setSort(sort: ComicSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        loadComics(_author, page = 1)
    }

    fun setStatus(status: ComicStatus) {
        if (_status.value == status) return
        _status.value = status
        loadComics(_author, page = 1)
    }

    private fun applyFilterAndSort() {
        val filtered = _comics.value.filter {
            when (_status.value) {
                ComicStatus.ALL -> true
                ComicStatus.FINISHED -> it.finished
                ComicStatus.ONGOING -> !it.finished
            }
        }
        _comics.value = when (_sort.value) {
            ComicSort.DD -> filtered.sortedByDescending { it.updatedAt }
            ComicSort.DA -> filtered.sortedBy { it.updatedAt }
            ComicSort.LD -> filtered.sortedByDescending { it.totalLikes }
            ComicSort.VD -> filtered.sortedByDescending { it.totalViews }
        }
    }

    fun saveScrollState(firstVisibleIndex: Int, currentPage: Int) {
        _savedFirstVisibleIndex = firstVisibleIndex
        _savedCurrentPage = currentPage
        _needsScrollRestore = true
    }
}
