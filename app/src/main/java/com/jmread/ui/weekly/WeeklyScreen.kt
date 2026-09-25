package com.jmread.ui.weekly

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicSummary
import com.jmread.core.model.WeekPeriod
import com.jmread.ui.browse.ComicGridView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 每周必看 VM：期数列表 + 选中期的内容流（/week，禁漫独有内容位） */
class WeeklyViewModel : ViewModel() {

    private val _periods = MutableStateFlow<List<WeekPeriod>>(emptyList())
    val periods: StateFlow<List<WeekPeriod>> = _periods.asStateFlow()

    private val _selected = MutableStateFlow<WeekPeriod?>(null)
    val selected: StateFlow<WeekPeriod?> = _selected.asStateFlow()

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _periodsError = MutableStateFlow<String?>(null)
    val periodsError: StateFlow<String?> = _periodsError.asStateFlow()

    private val _comicsError = MutableStateFlow<String?>(null)
    val comicsError: StateFlow<String?> = _comicsError.asStateFlow()

    private var loaded = false

    /** 首次进入：加载期数列表并选中最新一期 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _loading.value = true
        _periodsError.value = null
        viewModelScope.launch {
            try {
                val periods = JmRepository.weeklyPeriods()
                if (_periods.value.isEmpty() && periods.isNotEmpty()) {
                    _periods.value = periods
                    if (_selected.value == null) select(periods.first())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _periodsError.value = e.message ?: "每周必看加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 选择某期：拉取该期内容流 */
    fun select(period: WeekPeriod?) {
        if (period == null) return
        _selected.value = period
        _comics.value = emptyList()
        _loading.value = true
        _comicsError.value = null
        viewModelScope.launch {
            try {
                val result = JmRepository.weeklyContent(period.id, 1)
                _comics.value = result.items
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _comicsError.value = e.message ?: "该期内容加载失败"
            } finally {
                _loading.value = false
            }
        }
    }
}

/** 每周必看独立页（首页频道行入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: WeeklyViewModel = viewModel(),
) {
    val periods by viewModel.periods.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val periodsError by viewModel.periodsError.collectAsState()
    val comicsError by viewModel.comicsError.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("每周必看") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            periodsError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                items(periods, key = { it.id }) { p ->
                    FilterChip(
                        selected = p.id == selected?.id,
                        onClick = { viewModel.select(p) },
                        label = { Text(p.label.ifBlank { "第 ${p.id} 期" }) },
                    )
                }
            }
            when {
                loading && comics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                comicsError != null && comics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = comicsError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
                comics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "选择上方期数查看内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    ComicGridView(
                        comics = comics,
                        loading = loading,
                        endReached = true,
                        listState = gridState,
                        onLoadMore = {},
                        onComicClick = onComicClick,
                        showTailLoading = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
