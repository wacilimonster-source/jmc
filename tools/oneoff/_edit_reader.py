# -*- coding: utf-8 -*-
"""ReaderViewModel 单源改造"""
import io

p = 'app/src/main/java/com/jmread/ui/reader/ReaderViewModel.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('import com.jmread.core.source.SourceManager\nimport com.jmread.core.source.SourceType\n', 'import com.jmread.core.JmRepository\n')

s = s.replace('''    /** 作品标识 `源_id`：进度、已读、最近阅读、切片缓存都按它记账 */
    private var ref: String = ""

    /**
     * 本章固化使用的源，由 [load] 从作品标识解析。
     *
     * 不能运行时读 `SourceManager.current()`：阅读中切换数据源会让后续章节、
     * 图片与详情请求转到另一个源，按同一 id 取到完全不相干的内容。
     */
    var source: SourceType = SourceManager.activeSource.value
        private set

    private fun src(): Source = SourceManager.sourceOf(source)

''', '''    /** 作品标识：进度、已读、最近阅读、切片缓存都按它记账（单源 == comicId） */
    private var ref: String = ""

''')

s = s.replace('''        val (src, id) = com.jmread.core.source.ComicRef.parse(ref)
        this.source = src
        this.ref = ref
        this.comicId = id''', '''        this.ref = ref
        this.comicId = ref''')

s = s.replace('                        _pages.value = src().chapterPages(comicId, order)',
              '                        _pages.value = JmRepository.chapterPages(comicId, order)')
s = s.replace('                    _chapters.value = src().chapters(comicId)',
              '                    _chapters.value = JmRepository.chapters(comicId)')

io.open(p, 'w', encoding='utf-8').write(s)
print("done")
