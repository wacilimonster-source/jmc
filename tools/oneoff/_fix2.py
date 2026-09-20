# -*- coding: utf-8 -*-
import io

p = 'app/src/main/java/com/jmread/ui/category/CategoryScreen.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''    LaunchedEffect(activeSource) {
        viewModel.loadCategories()
    }''', '''    LaunchedEffect(Unit) {
        viewModel.loadCategories()
    }''')

s = s.replace('''    LaunchedEffect(categoryId, activeSource) {
        viewModel.loadCategories()
        // 同分类同源且已有数据时（从详情返回重组）VM 内部会跳过重载，保留累积分页与滚动状态
        viewModel.loadComics(page = 1, category = categoryId, reloadKey = "$activeSource:$categoryId")
    }

    // 当前源不支持当前排序时回退到默认
    LaunchedEffect(activeSource, supportedSorts) {
        if (viewModel.sort.value !in supportedSorts) {
            viewModel.setSort(supportedSorts.first())
        }
    }''', '''    LaunchedEffect(categoryId) {
        viewModel.loadCategories()
        // 同分类且已有数据时（从详情返回重组）VM 内部会跳过重载，保留累积分页与滚动状态
        viewModel.loadComics(page = 1, category = categoryId, reloadKey = categoryId)
    }

    // 当前排序不在能力声明内时回退到默认
    LaunchedEffect(supportedSorts) {
        if (viewModel.sort.value !in supportedSorts) {
            viewModel.setSort(supportedSorts.first())
        }
    }''')

io.open(p, 'w', encoding='utf-8').write(s)
print("done")
