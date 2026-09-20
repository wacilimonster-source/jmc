# -*- coding: utf-8 -*-
"""SearchScreen 单源/四维改造脚本"""
import io

p = 'app/src/main/java/com/jmread/ui/search/SearchScreen.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''    val searchError by viewModel.searchError.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()''',
'''    val searchError by viewModel.searchError.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val dimension by viewModel.dimension.collectAsState()
    val total by viewModel.total.collectAsState()''')

s = s.replace('''    LaunchedEffect(Unit) {
        viewModel.loadHotWords()
        viewModel.loadTags()
    }''',
'''    LaunchedEffect(Unit) {
        viewModel.loadTags()
    }''')

s = s.replace('''                                contentDescription = "标签筛选",
                                tint = if (selectedTag != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },''',
'''                                contentDescription = "官方标签",
                                tint = if (dimension == com.jmread.ui.search.SearchDimension.TAG) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },''')

old_sort = '''        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = currentSort == ComicSort.DD,
                    onClick = { onSortChange(ComicSort.DD) },
                    label = { Text("新到旧") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.DA,
                    onClick = { onSortChange(ComicSort.DA) },
                    label = { Text("旧到新") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.LD,
                    onClick = { onSortChange(ComicSort.LD) },
                    label = { Text("最多喜欢") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.VD,
                    onClick = { onSortChange(ComicSort.VD) },
                    label = { Text("最多观看") },
                )
            }
            com.jmread.ui.browse.readFilterOptions.forEach { (f, label) ->'''

new_sort = '''        // 四维 Tab（综合/作品/作者/标签）：main_tag 实测各有独立结果集与真实 total
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchDimension.entries.forEach { d ->
                item(key = d.name) {
                    FilterChip(
                        selected = dimension == d,
                        onClick = { viewModel.setDimension(d) },
                        label = { Text(d.label) },
                    )
                }
            }
        }
        // 排序：读能力声明（本源无「旧到新」，不出现该选项）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.jmread.core.JmCapabilities.supportedSorts.forEach { s ->
                item(key = s.name) {
                    FilterChip(
                        selected = currentSort == s,
                        onClick = { onSortChange(s) },
                        label = { Text(s.label) },
                    )
                }
            }
            com.jmread.ui.browse.readFilterOptions.forEach { (f, label) ->'''

assert old_sort in s, "sort block not found"
s = s.replace(old_sort, new_sort)

old_grid = '''        } else {
            Column(Modifier.fillMaxSize()) {
                ComicGridView(
                    comics = displayComics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,'''
new_grid = '''        } else {
            Column(Modifier.fillMaxSize()) {
                if (total > 0 && readFilter == com.jmread.ui.browse.ReadFilter.ALL) {
                    Text(
                        text = "共 $total 条结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                ComicGridView(
                    comics = displayComics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,'''
assert old_grid in s, "grid block not found"
s = s.replace(old_grid, new_grid)

old_sheet = '''                Text(
                    text = "标签筛选",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "与关键词为「且」关系，单选标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    FilterChip(
                        selected = selectedTag == null,
                        onClick = {
                            showTagSheet = false
                            viewModel.selectTag(null)
                        },
                        label = { Text("全部") },
                    )
                    tags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = {
                                showTagSheet = false
                                viewModel.selectTag(tag)
                            },
                            label = { Text(tag) },
                        )
                    }
                }'''
new_sheet = '''                Text(
                    text = "官方标签",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "点击标签进入标签维度搜索",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                showTagSheet = false
                                viewModel.searchTag(tag)
                            },
                            label = { Text(tag) },
                        )
                    }
                }'''
assert old_sheet in s, "sheet block not found"
s = s.replace(old_sheet, new_sheet)

io.open(p, 'w', encoding='utf-8').write(s)
print("done")
