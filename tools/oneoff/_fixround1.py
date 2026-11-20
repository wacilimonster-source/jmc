# -*- coding: utf-8 -*-
"""第一轮编译错误修复：capabilities 频道位、promote 类型、smart cast、MineScreen 括号、ChannelScreen import"""
import io

# 1. Capabilities: add hasPromote/hasChannels after hasRelated
p = 'app/src/main/java/com/jmread/core/JmCapabilities.kt'
s = io.open(p, encoding='utf-8').read()
old = '''    const val hasRelated = true
    /** /random 不存在；o=md 是稳定序不是随机序 */'''
new = '''    const val hasRelated = true
    /** 推荐本本（/promote，内容轮换、best-effort 容空）—— 2026-09-25 实测 */
    const val hasPromote = true
    /** 运营频道（禁漫汉化组/禁漫去码/全彩化，基于 /search 关键词） */
    const val hasChannels = true
    /** /random 不存在；o=md 是稳定序不是随机序 */'''
assert old in s, "caps"
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(s)

# 2. ChannelScreen: missing lazy items import
p = 'app/src/main/java/com/jmread/ui/channel/ChannelScreen.kt'
s = io.open(p, encoding='utf-8').read()
old = 'import androidx.compose.foundation.lazy.LazyRow\n'
assert old in s, "channel import"
s = s.replace(old, 'import androidx.compose.foundation.lazy.LazyRow\nimport androidx.compose.foundation.lazy.items\n')
io.open(p, 'w', encoding='utf-8').write(s)

# 3. PromoteScreen: use repository PromoteBlock directly
p = 'app/src/main/java/com/jmread/ui/channel/PromoteScreen.kt'
s = io.open(p, encoding='utf-8').read()
old = '''    fun load() {
        if (_loading.value || _blocks.value.isNotEmpty()) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _blocks.value = JmRepository.promoteBlocks().filter { it.comics.isNotEmpty() }'''
new = '''    fun load() {
        if (_loading.value || _blocks.value.isNotEmpty()) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _blocks.value = JmRepository.promoteBlocks().filter { it.comics.isNotEmpty() }'''
assert old in s, "promote vm"
s = s.replace(old, new)
old = '''data class PromoteBlockUi(
    val id: String,
    val title: String,
    val comics: List<ComicSummary>,
)

'''
assert old in s, "promote ui model"
s = s.replace(old, '')
s = s.replace('private val _blocks = MutableStateFlow<List<PromoteBlockUi>>(emptyList())',
              'private val _blocks = MutableStateFlow<List<com.jmread.core.PromoteBlock>>(emptyList())')
io.open(p, 'w', encoding='utf-8').write(s)

# 4. FollowFeedScreen: smart cast fix
p = 'app/src/main/java/com/jmread/ui/follow/FollowFeedScreen.kt'
s = io.open(p, encoding='utf-8').read()
old = '''                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )'''
assert old in s, "follow smartcast"
s = s.replace(old, '''                            Text(
                                text = error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )''')
io.open(p, 'w', encoding='utf-8').write(s)

# 5. HomeScreen: smart cast fixes for rankError
p = 'app/src/main/java/com/jmread/ui/home/HomeScreen.kt'
s = io.open(p, encoding='utf-8').read()
old = '''                if (rankError != null && rankComics.isNotEmpty()) {'''
assert old in s, "home err1"
s = s.replace(old, '''                val err = rankError
                if (err != null && rankComics.isNotEmpty()) {''')
s = s.replace('''                        Text(
                            text = rankError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )''', '''                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )''')
s = s.replace('''                    rankComics.isEmpty() && rankError != null && !rankLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = rankError,''', '''                    rankComics.isEmpty() && err != null && !rankLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = err,''')
io.open(p, 'w', encoding='utf-8').write(s)

# 6. MineScreen: fix broken brace tail
p = 'app/src/main/java/com/jmread/ui/mine/MineScreen.kt'
s = io.open(p, encoding='utf-8').read()
old = '''        )
    }

    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }
}'''
new = '''        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }
}'''
assert old in s, "mine tail"
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(s)

print('done')
