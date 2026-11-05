package com.jmread.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jmread.core.JmRepository
import com.jmread.data.OfficialTags

/**
 * 发现中心：官方标签（全部分组默认展开，置于页首）与运营频道入口。
 * 「按分类找作品」已移除；推荐本本 / 每周必看入口上移至首页顶栏。
 * 标签词表为本地常量；点击标签进入该标签的作品流。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    onTagClick: (String) -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
) {
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

        // ── 官方标签：全部分组默认展开，置于发现页最上方 ─────────────
        Text(
            text = "官方标签",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = "点击标签进入该标签的作品流",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
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
                        onClick = { onTagClick(tag) },
                        label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        // ── 运营频道 ─────────────────────────────────────────────────
        Text(
            text = "运营频道",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp),
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
