package com.jmread.ui.favourite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.model.ComicSummary
import com.jmread.data.Bookmarks
import com.jmread.ui.browse.ComicGridView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 本地书架 VM：免登录可用的主收藏形态 */
class BookmarkViewModel : ViewModel() {
    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics.asStateFlow()

    init {
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
    }

    /** 移出书架（卡片长按菜单/删除按钮） */
    fun remove(comicId: String) {
        viewModelScope.launch { Bookmarks.remove(comicId) }
    }
}

/** 本地书架页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: BookmarkViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val listState = rememberLazyGridState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地书架") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        if (comics.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "书架空空的\n在详情页点心形即可收藏",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ComicGridView(
                comics = comics,
                loading = false,
                endReached = true,
                listState = listState,
                onLoadMore = {},
                onComicClick = onComicClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                showTailLoading = false,
            )
        }
    }
}
