package com.jmread.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicChapter
import com.jmread.core.model.ComicComment
import com.jmread.core.model.ComicDetail
import com.jmread.core.model.ComicSummary
import com.jmread.core.log.LogStore
import com.jmread.data.Bookmarks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 漫画详情 VM：基本信息 + 章节列表 + 相关推荐 + 评论区 */
class ComicDetailViewModel : ViewModel() {

    private val _comic = MutableStateFlow<ComicDetail?>(null)
    val comic: StateFlow<ComicDetail?> = _comic

    private val _chapters = MutableStateFlow<List<ComicChapter>>(emptyList())
    val chapters: StateFlow<List<ComicChapter>> = _chapters

    /** 章节列表加载失败原因（null = 无错误） */
    private val _chaptersError = MutableStateFlow<String?>(null)
    val chaptersError: StateFlow<String?> = _chaptersError

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // ---- 相关推荐 ----
    private val _recommendations = MutableStateFlow<List<ComicSummary>>(emptyList())
    val recommendations: StateFlow<List<ComicSummary>> = _recommendations

    // ---- 评论区 ----
    private val _comments = MutableStateFlow<List<ComicComment>>(emptyList())
    val comments: StateFlow<List<ComicComment>> = _comments

    private val _commentLoading = MutableStateFlow(false)
    val commentLoading: StateFlow<Boolean> = _commentLoading

    private val _commentEndReached = MutableStateFlow(false)
    val commentEndReached: StateFlow<Boolean> = _commentEndReached

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    var commentPage: Int = 1
        private set

    /**
     * 楼中楼展示：commentId -> 展开的子评论。
     * 子评论内嵌在 /forum 响应的 replys 里（无需新端点），
     * loadComments 拉到后先存 [allReplies]，展开时从这里取。
     */
    private val allReplies = HashMap<String, List<ComicComment>>()
    private val _subComments = MutableStateFlow<Map<String, List<ComicComment>>>(emptyMap())
    val subComments: StateFlow<Map<String, List<ComicComment>>> = _subComments

    private val _replyingTo = MutableStateFlow<String?>(null)
    val replyingTo: StateFlow<String?> = _replyingTo

    var loadedComicId: String = ""
        private set

    private var loadedRef: String = ""

    /** 加载代数：切换漫画时自增，旧协程回调前校验，防止脏数据覆盖新漫画状态 */
    private var loadGeneration = 0
    private var loadJob: kotlinx.coroutines.Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var commentSupported: Boolean = true
        private set

    /** 本地历史进度（上次阅读到第几话第几页），无则 null */
    private val _lastProgress = MutableStateFlow<com.jmread.data.ReaderPrefs.Progress?>(null)
    val lastProgress: StateFlow<com.jmread.data.ReaderPrefs.Progress?> = _lastProgress

    fun load(ref: String) {
        if (loadedRef == ref && _comic.value != null) return
        val comicId = ref // 单源：路由参数即漫画 id
        loadedRef = ref
        loadedComicId = comicId
        loadJob?.cancel()
        loadJob = null
        val gen = ++loadGeneration
        _error.value = null
        _chaptersError.value = null
        _subComments.value = emptyMap()
        allReplies.clear()
        _replyingTo.value = null
        _commentEndReached.value = false
        _commentError.value = null
        // 必须复位：loadComments 用 _commentLoading 作重入锁
        _commentLoading.value = false
        _comments.value = emptyList()
        _recommendations.value = emptyList()
        commentPage = 1
        // 读取本地历史进度（不阻塞主线程）
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (gen != loadGeneration) return@launch
            _lastProgress.value = runCatching {
                com.jmread.data.ReaderPrefs.current().lastProgressAsync(ref)
            }.getOrNull()
        }
        loadJob = viewModelScope.launch {
            val chaptersJob = launch {
                _loading.value = true
                try {
                    val list = JmRepository.chapters(comicId)
                    if (gen != loadGeneration) return@launch
                    _chapters.value = list
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen == loadGeneration) {
                        _chaptersError.value = e.message?.takeIf { it.isNotBlank() } ?: "章节加载失败"
                    }
                } finally {
                    if (gen == loadGeneration) _loading.value = false
                }
            }
            try {
                val detail = JmRepository.comicDetail(comicId)
                if (gen != loadGeneration) return@launch
                // 云端收藏态只读回填展示；心形操作本地书架（见 favourite()）
                _favourited.value = Bookmarks.contains(comicId)
                _comic.value = detail.also {
                    if (it.updatedAt.isNotBlank()) {
                        com.jmread.data.UpdatedAtCache.put(ref, it.updatedAt)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) {
                    _error.value = e.message ?: "加载失败"
                }
            }
            chaptersJob.join()
        }
        loadRecommendations(comicId, gen)
        loadComments(comicId, page = 1, gen = gen)
        observeDownloaded(comicId)
    }

    /** 相关推荐 */
    fun loadRecommendations(comicId: String, gen: Int = loadGeneration) {
        viewModelScope.launch {
            try {
                val list = JmRepository.recommendations(comicId)
                if (gen == loadGeneration) _recommendations.value = list
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) _recommendations.value = emptyList()
            }
        }
    }

    /** 章节列表加载失败后的重试入口：只重拉章节，不动详情/评论/推荐 */
    fun retryChapters(comicId: String) {
        val gen = loadGeneration
        viewModelScope.launch {
            _chaptersError.value = null
            _loading.value = true
            try {
                val list = JmRepository.chapters(comicId)
                if (gen != loadGeneration) return@launch
                _chapters.value = list
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) {
                    _chaptersError.value = e.message?.takeIf { it.isNotBlank() } ?: "章节加载失败"
                }
            } finally {
                if (gen == loadGeneration) _loading.value = false
            }
        }
    }

    /** 已下载章节号集合（供 UI 查表；章节/下载任务变化时 IO 线程重算一次） */
    private val _downloadedOrders = kotlinx.coroutines.flow.MutableStateFlow<Set<Int>>(emptySet())
    val downloadedOrders: kotlinx.coroutines.flow.StateFlow<Set<Int>> = _downloadedOrders

    private var downloadedJob: kotlinx.coroutines.Job? = null

    fun observeDownloaded(comicId: String) {
        downloadedJob?.cancel()
        downloadedJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(_chapters, com.jmread.core.download.DownloadManager.tasks) { chs, _ -> chs }
                .collectLatest { chs ->
                    val set = chs.asSequence()
                        .map { it.order }
                        .filter { com.jmread.core.download.DownloadManager.isDownloaded(comicId, it) }
                        .toSet()
                    _downloadedOrders.value = set
                }
        }
    }

    /** 下载指定章节（入队，由 DownloadManager 调度） */
    fun downloadChapter(comicId: String, comic: ComicDetail?, chapter: ComicChapter) {
        com.jmread.core.download.DownloadManager.enqueue(
            comicId = comicId,
            comicTitle = comic?.title ?: comicId,
            coverUrl = comic?.coverUrl ?: "",
            order = chapter.order,
            epTitle = chapter.title,
            // 单章页数运行时才可知，传 0 由 runTask 拉取真实页数后回填
            pageCount = 0,
        )
    }

    /** 下载整本漫画（跳过已下载章节） */
    fun downloadAll(comicId: String, comic: ComicDetail?, chapters: List<ComicChapter>) {
        com.jmread.core.download.DownloadManager.enqueueAll(
            comicId = comicId,
            comicTitle = comic?.title ?: comicId,
            coverUrl = comic?.coverUrl ?: "",
            chapters = chapters.map { it.order to it.title },
        )
    }

    // ── 收藏（本地书架）──────────────────────────────────────────────────
    private val _favourited = MutableStateFlow(false)
    val favourited: StateFlow<Boolean> = _favourited

    private val _favouriteError = MutableStateFlow<String?>(null)
    val favouriteError: StateFlow<String?> = _favouriteError

    /** 心形操作本地书架（免登录可用；云端收藏态只读展示） */
    fun canFavourite(): Boolean = true

    /** 收藏 / 取消收藏（切换本地书架） */
    fun favourite() {
        val comic = _comic.value ?: return
        val comicId = loadedComicId
        if (comicId.isEmpty()) return
        val added = Bookmarks.toggle(
            comicId = comicId,
            title = comic.title,
            author = comic.author,
            coverUrl = comic.coverUrl ?: "",
            note = comic.updatedAt,
        )
        _favourited.value = added
        LogStore.log("Detail", "I", "bookmark toggled: comic=$comicId, added=$added")
    }

    fun consumeFavouriteError(): String? {
        val v = _favouriteError.value
        _favouriteError.value = null
        return v
    }

    // ── 评论 ──────────────────────────────────────────────────────────────

    /** 评论加载序号：send()/新加载接管后，旧请求的收尾复位必须失效 */
    private var commentSeq = 0

    /** 分页加载评论（page=1 时重置） */
    fun loadComments(comicId: String, page: Int, gen: Int = loadGeneration) {
        if (_commentLoading.value) return
        if (page > 1 && _commentEndReached.value) return
        _commentLoading.value = true
        _commentError.value = null
        val seq = ++commentSeq
        viewModelScope.launch {
            try {
                val result = JmRepository.comments(comicId, page)
                if (gen != loadGeneration) return@launch
                _comments.value = if (page == 1) result.items else _comments.value + result.items
                // 子评论内嵌在响应里，缓存供展开时取（不重复请求）
                result.items.forEach { c ->
                    if (c.replies.isNotEmpty()) allReplies[c.id] = c.replies
                }
                _commentEndReached.value = page >= result.pages
                commentPage = page
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) _commentError.value = e.message ?: "评论加载失败"
            } finally {
                if (gen == loadGeneration && seq == commentSeq) _commentLoading.value = false
            }
        }
    }

    fun setReplyingTo(commentId: String?) {
        _replyingTo.value = commentId
    }

    /** 发表评论 / 回复：能力位 false（参数与风控未验证），UI 不渲染入口；此方法兜底报错 */
    fun send(content: String, onSent: (String?) -> Unit = {}) {
        onSent("评论功能未开放")
    }

    /** 展开 / 收起楼中楼（子评论随主评论一并返回，本地切换） */
    fun toggleSubComments(commentId: String) {
        val current = _subComments.value
        if (current.containsKey(commentId)) {
            _subComments.value = current - commentId
            return
        }
        val replies = allReplies[commentId] ?: return
        _subComments.value = current + (commentId to replies)
    }
}
