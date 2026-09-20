# -*- coding: utf-8 -*-
import io

# 1. JmApp: component registration signature
p = 'app/src/main/java/com/jmread/JmApp.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''                .components {
                    add(JmUrlKeyer())
                    add(String::class, JmImageFetcher.Factory())
                }''', '''                .components {
                    add(JmUrlKeyer())
                    add(JmImageFetcher.Factory())
                }''')
io.open(p, 'w', encoding='utf-8').write(s)

# 2. JmRepository: ComicCategorySub + BROWSE_HARD_CAP import
p = 'app/src/main/java/com/jmread/core/JmRepository.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import com.jmread.core.model.ComicCategory\n', 'import com.jmread.core.model.BROWSE_HARD_CAP\nimport com.jmread.core.model.ComicCategory\n')
s = s.replace('subCategories = c.subCategories.map { ComicCategorySub(it.slug, it.name) },',
              'subCategories = c.subCategories.map { JmSubCategoryNode(it.slug, it.name) },')
s = s.replace('''data class ComicCategorySub(val slug: String, val name: String)''',
              '''data class JmSubCategoryNode(val slug: String, val name: String)''')
io.open(p, 'w', encoding='utf-8').write(s)

# 3. DomainPool failCount type
p = 'app/src/main/java/com/jmread/core/netconfig/DomainPool.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''            } else {
                failCount = next
            }''', '''            } else {
                failCount = next.toInt()
            }''')
io.open(p, 'w', encoding='utf-8').write(s)

# 4. JmImageFetcher: nullable create + ImageLoader import
p = 'app/src/main/java/com/jmread/core/scramble/JmImageFetcher.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import coil.ImageLoader\n', '')
s = s.replace('''    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher =
            JmImageFetcher(data, options)
    }''', '''    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: coil.ImageLoader): Fetcher =
            JmImageFetcher(data, options)
    }''')
io.open(p, 'w', encoding='utf-8').write(s)

# 5. MainScreen: collectAsState import
p = 'app/src/main/java/com/jmread/ui/MainScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue',
              'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.getValue')
io.open(p, 'w', encoding='utf-8').write(s)

# 7. BookmarkScreen: collect in launch
p = 'app/src/main/java/com/jmread/ui/favourite/BookmarkScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    init {
        Bookmarks.items.collect {
            _comics.value = it.map { b ->
                ComicSummary(
                    id = b.comicId,
                    title = b.title,
                    author = b.author,
                    coverUrl = b.coverUrl.ifBlank { null },
                    updatedAt = b.note,
                )
            }
        }
    }''', '''    init {
        viewModelScope.launch {
            Bookmarks.items.collect {
                _comics.value = it.map { b ->
                    ComicSummary(
                        id = b.comicId,
                        title = b.title,
                        author = b.author,
                        coverUrl = b.coverUrl.ifBlank { null },
                        updatedAt = b.note,
                    )
                }
            }
        }
    }''')
io.open(p, 'w', encoding='utf-8').write(s)

# 8. HomeScreen WeekTab items import
p = 'app/src/main/java/com/jmread/ui/home/HomeScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import androidx.compose.foundation.lazy.grid.rememberLazyGridState',
              'import androidx.compose.foundation.lazy.LazyRow\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.grid.rememberLazyGridState')
s = s.replace('''        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.lazy.items(periods, key = { it.id }) { p ->
                FilterChip(
                    selected = p.id == selected?.id,
                    onClick = { onPeriodChange(p) },
                    label = { Text(p.label.ifBlank { "第 ${p.id} 期" }) },
                )
            }
        }''', '''        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(periods, key = { it.id }) { p ->
                FilterChip(
                    selected = p.id == selected?.id,
                    onClick = { onPeriodChange(p) },
                    label = { Text(p.label.ifBlank { "第 ${p.id} 期" }) },
                )
            }
        }''')
s = s.replace('import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Row',
              'import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.foundation.layout.Row')
io.open(p, 'w', encoding='utf-8').write(s)

# 9. ReaderViewModel: stale Source import
p = 'app/src/main/java/com/jmread/ui/reader/ReaderViewModel.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import com.jmread.core.source.Source\n', '')
io.open(p, 'w', encoding='utf-8').write(s)

# 10. SettingsScreen: OutlinedTextField import
p = 'app/src/main/java/com/jmread/ui/settings/SettingsScreen.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('import androidx.compose.material3.Scaffold\n',
              'import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Scaffold\n')
io.open(p, 'w', encoding='utf-8').write(s)

print("done")
