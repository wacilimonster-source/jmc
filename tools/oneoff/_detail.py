# -*- coding: utf-8 -*-
"""ComicDetailViewModel：收藏写侧（登录=云端翻转 POST /favorite；未登录=本地书架）+ 心形初值改列表缓存"""
import io

p = 'app/src/main/java/com/jmread/ui/detail/ComicDetailViewModel.kt'
s = io.open(p, encoding='utf-8').read()

# 1. imports
old = '''import com.jmread.core.JmRepository
import com.jmread.core.model.ComicChapter'''
new = '''import com.jmread.core.JmCapabilities
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicChapter'''
assert old in s, "imports"
s = s.replace(old, new)

# 2. load(): heart initial value — cloud ids cache when logged in, local bookmarks otherwise
old = '''                val detail = JmRepository.comicDetail(comicId)
                if (gen != loadGeneration) return@launch
                // 云端收藏态只读回填展示；心形操作本地书架（见 favourite()）
                _favourited.value = Bookmarks.contains(comicId)'''
new = '''                val detail = JmRepository.comicDetail(comicId)
                if (gen != loadGeneration) return@launch
                // 心形初值：登录=云端收藏 id 集合（/album.is_favorite 恒 false 不可信），
                // 未登录=本地书架。心形的操作语义见 favourite()
                _favourited.value = if (JmRepository.isLoggedIn) {
                    runCatching { JmRepository.isCloudFavourite(comicId) }.getOrDefault(false)
                } else {
                    Bookmarks.contains(comicId)
                }'''
assert old in s, "load initial"
s = s.replace(old, new)

# 3. favourite(): write side
old = '''    /** 心形操作本地书架（免登录可用；云端收藏态只读展示） */
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
    }'''
new = '''    /** 心形可用：登录走云端翻转，未登录走本地书架 */
    fun canFavourite(): Boolean = true

    /** 防双击：翻转在途时忽略再次点击（云端端点是开关，连点两次=加了又取消） */
    private var togglingFavourite = false

    /**
     * 收藏 / 取消收藏。
     * 已登录：POST /favorite {aid} 翻转端点，以服务端响应 type=add|remove 回写
     * （2026-09-25 实测，响应机器可读；不本地乐观翻转）。
     * 未登录：本地书架。
     */
    fun favourite() {
        val comic = _comic.value ?: return
        val comicId = loadedComicId
        if (comicId.isEmpty() || togglingFavourite) return
        viewModelScope.launch {
            if (JmRepository.isLoggedIn && JmCapabilities.hasCloudFavouriteWrite) {
                togglingFavourite = true
                try {
                    val now = JmRepository.favourite(comicId)
                    _favourited.value = now
                    LogStore.log("Detail", "I", "cloud favourite toggled: comic=$comicId, favourited=$now")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _favouriteError.value = e.message ?: "收藏操作失败"
                    LogStore.log("Detail", "E", "cloud favourite failed: comic=$comicId, error=${e.message}")
                } finally {
                    togglingFavourite = false
                }
            } else {
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
        }
    }'''
assert old in s, "favourite"
s = s.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(s)
print('done')
