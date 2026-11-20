package com.jmread.ui.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmread.core.model.AuthorName
import com.jmread.core.model.ParsedAuthor

/**
 * 作者选择层（对标 PiKA v1.10.0 AuthorPickerSheet）。
 *
 * 复合署名（联合署名/带翻译）点击作者时弹出：任选一段即按该片段搜索「署名包含它」的作品；
 * 「完整署名」永远是兜底出口。单一署名不经过本层（详情页一次点击直达）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorPickerSheet(
    origin: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val parsed: ParsedAuthor = remember(origin) { AuthorName.parse(origin) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "选择要查看的作者",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "原署名：$origin",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            parsed.segments.forEach { seg ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(seg.text) }
                        .padding(vertical = 12.dp),
                ) {
                    AssistChip(
                        onClick = { onPick(seg.text) },
                        label = { Text(seg.text, maxLines = 1) },
                    )
                    if (seg.role != com.jmread.core.model.AuthorRole.UNKNOWN) {
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = seg.role.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(origin) }
                    .padding(vertical = 12.dp),
            ) {
                AssistChip(onClick = { onPick(origin) }, label = { Text("完整署名", maxLines = 1) })
                Text(
                    text = "按整串署名搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
