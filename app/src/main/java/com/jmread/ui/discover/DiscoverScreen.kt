package com.jmread.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmread.core.JmRepository
import com.jmread.core.model.ComicCategory
import com.jmread.data.CategorySettings
import com.jmread.data.applyCategorySettings
import com.jmread.data.OfficialTags
import com.jmread.ui.browse.BrowseViewModel
import com.jmread.ui.category.CategoryReorderDialog

/**
 * 发现中心（阶段 B）：分类、官方标签、精选内容（推荐本本 / 每周必看 / 运营频道）
 * 的共同入口。底栏「分类」升级为「发现」；旧分类路由与参数保持可达。
 * 分类名与标签全部来自真实数据；各类列表的加载、错误与排序沿用原有路径。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    onCategoryClick: (String) -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onOpenPromote: () -> Unit = {},
    onOpenWeekly: () -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val categoriesLoading by viewModel.categoriesLoading.collectAsState()
    val categoriesError by viewModel.categoriesError.collectAsState()
    val categoriesReady by viewModel.categoriesReady.collectAsState()
    var categorySettings by remember { mutableStateOf(CategorySettings.get()) }
    var showSettings by remember { mutableStateOf(false) }
    var showAllTags by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 仓库层有内存缓存：同一进程内重复进入发现页不会重复请求
        viewModel.loadCategories()
    }

    val topLevelCategories = remember(categories, categorySettings) {
        applyCategorySettings(categories, categorySettings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "发现",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        // ── 按分类找作品 ─────────────────────────────────────────────
        DiscoverSectionHead(
            title = "按分类找作品",
            action = "管理",
            onAction = { showSettings = true },
        )
        when {
            categoriesError != null && topLevelCategories.isEmpty() -> {
                DiscoverErrorRow(
                    message = categoriesError ?: "分类加载失败",
                    onRetry = { viewModel.loadCategories() },
                )
            }

            topLevelCategories.isEmpty() && categoriesLoading -> {
                // 与卡片同形的占位，避免加载完成后跳动
                CategoryCardSkeletons()
            }

            topLevelCategories.isEmpty() && categoriesReady -> {
                Text(
                    text = "暂无分类",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            else -> {
                // 静态官方分类卡：进入原分类作品流（子分类与排序参数保持）
                topLevelCategories.chunked(2).forEach { rowCategories ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowCategories.forEach { category ->
                            CategoryCard(
                                category = category,
                                onClick = { onCategoryClick(category.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowCategories.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        // ── 官方标签 ─────────────────────────────────────────────────
        DiscoverSectionHead(
            title = "官方标签",
            action = "全部标签",
            onAction = { showAllTags = true },
        )
        // 首屏只放最常用的一组词表，完整分组在「全部标签」面板
        val quickTags = remember { OfficialTags.groups.firstOrNull()?.tags.orEmpty().take(8) }
        if (quickTags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickTags.forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = { onTagClick(tag) },
                        label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        } else {
            // 词表是常量，正常不会为空；防御性提示而不是空白
            Text(
                text = "标签词表加载中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        // ── 精选内容 ─────────────────────────────────────────────────
        Text(
            text = "精选内容",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        DiscoverMenuGroup {
            DiscoverMenuRow(
                title = "推荐本本",
                subtitle = "按来源编排的精选内容",
                onClick = onOpenPromote,
            )
            DiscoverMenuDivider()
            DiscoverMenuRow(
                title = "每周必看",
                subtitle = "选择期数，查看当期作品",
                onClick = onOpenWeekly,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "运营频道",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        DiscoverMenuGroup {
            JmRepository.channels.forEachIndexed { index, channel ->
                if (index > 0) DiscoverMenuDivider()
                DiscoverMenuRow(
                    title = channel.label,
                    subtitle = channel.description,
                    onClick = { onOpenChannel(channel.id) },
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (showSettings && categories.isNotEmpty()) {
        CategoryReorderDialog(
            categories = categories,
            initial = categorySettings,
            onDismiss = { showSettings = false },
            onSave = { settings ->
                CategorySettings.save(settings)
                categorySettings = settings
                showSettings = false
            },
        )
    }

    if (showAllTags) {
        ModalBottomSheet(onDismissRequest = { showAllTags = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Text("官方标签", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "点击标签进入该标签的作品流",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                OfficialTags.groups.forEach { group ->
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        group.tags.forEach { tag ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    showAllTags = false
                                    onTagClick(tag)
                                },
                                label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverSectionHead(
    title: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = action,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

/** 分类入口卡：与原型一致的两列卡片，名称来自真实分类 */
@Composable
private fun CategoryCard(
    category: ComicCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (category.totalCount > 0) {
                    // 真实存量单独放标题行右侧，不挤占「查看分类与子分类」说明
                    Text(
                        text = "${category.totalCount} 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "查看分类与子分类",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryCardSkeletons() {
    repeat(2) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) {
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(3.4f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DiscoverErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "重试",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
    // 分类失败时静态标签与精选内容仍可用（I05 验收）
}

@Composable
private fun DiscoverMenuGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun DiscoverMenuDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
private fun DiscoverMenuRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(18.dp),
        )
    }
}
