# -*- coding: utf-8 -*-
"""DownloadScreen / FollowManageScreen / CategoryScreen 单源改造"""
import io

# DownloadScreen: ComicRef.ofName(source, comicId) -> comicId
p = 'app/src/main/java/com/jmread/ui/download/DownloadScreen.kt'
s = io.open(p, encoding='utf-8').read()
old = '''                                if (comicTasks.any { t -> t.isFinished && t.task.order == order }) {
                                    onComicClick(
                                        com.jmread.core.source.ComicRef.ofName(
                                            comicTasks.firstOrNull()?.task?.source, comicId,
                                        ),
                                        order,
                                    )
                                }'''
new = '''                                if (comicTasks.any { t -> t.isFinished && t.task.order == order }) {
                                    onComicClick(comicId, order)
                                }'''
assert old in s, "DownloadScreen block not found"
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(s)

# FollowManageScreen: tags via repository
p = 'app/src/main/java/com/jmread/ui/follow/FollowManageScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import com.jmread.core.source.SourceManager\n', 'import com.jmread.core.JmRepository\n')
s = s.replace('tagList = com.jmread.core.runCatchingCancellable { SourceManager.current().tags() }',
              'tagList = com.jmread.core.runCatchingCancellable { JmRepository.tags() }')
io.open(p, 'w', encoding='utf-8').write(s)

# CategoryScreen
p = 'app/src/main/java/com/jmread/ui/category/CategoryScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import com.jmread.core.source.SourceManager\n', 'import com.jmread.core.JmCapabilities\n')
s = s.replace('''    val activeSource by SourceManager.activeSource.collectAsState()
''', '')
s = s.replace('val supportedSorts = remember(activeSource) { SourceManager.current().supportedSorts }',
              'val supportedSorts = remember { JmCapabilities.supportedSorts }')
io.open(p, 'w', encoding='utf-8').write(s)
print("done")
