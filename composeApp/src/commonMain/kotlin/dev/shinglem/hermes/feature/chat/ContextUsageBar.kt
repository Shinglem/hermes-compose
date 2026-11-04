package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.chat_usage_hint
import dev.shinglem.hermes.resources.chat_usage_high
import dev.shinglem.hermes.resources.chat_usage_total
import dev.shinglem.hermes.resources.chat_usage_total_detail
import dev.shinglem.hermes.resources.chat_usage_unknown

/**
 * 上下文用量条：`已用 / 上限（百分比）`。
 *
 * 点一下才拉 `session.context_breakdown`（明细比 `session.usage` 贵，别每次会话都拉）。
 * 没有数据时整条不渲染 —— 空进度条比没有更让人困惑。
 */
@Composable
fun ContextUsageBar(
    usage: SessionUsage?,
    detail: String?,
    onShowDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (usage == null || !usage.hasData) return
    var open by remember { mutableStateOf(false) }
    val color = when (usage.level) {
        UsageLevel.HIGH -> MaterialTheme.colorScheme.error
        UsageLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
        UsageLevel.LOW -> MaterialTheme.colorScheme.primary
    }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable {
                open = true
                onShowDetail()
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { usage.ratio },
                    modifier = Modifier.width(64.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Text(
                    text = usageLabel(usage),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (usage.level == UsageLevel.HIGH) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = detail ?: usageLabel(usage),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (usage.totalTokens > 0) {
                    Text(
                        text = stringResource(
                            Res.string.chat_usage_total_detail,
                            formatTokens(usage.totalTokens),
                            usage.calls,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(Res.string.chat_usage_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun usageLabel(usage: SessionUsage): String {
    val used = formatTokens(usage.contextUsed)
    val total = if (usage.totalTokens > 0) {
        " · " + stringResource(Res.string.chat_usage_total, formatTokens(usage.totalTokens))
    } else {
        ""
    }
    return when {
        usage.contextMax > 0 -> {
            val percent = (usage.ratio * 100).toInt()
            val suffix = if (usage.level == UsageLevel.HIGH) {
                " · " + stringResource(Res.string.chat_usage_high)
            } else {
                ""
            }
            "$used / ${formatTokens(usage.contextMax)}（$percent%）$suffix$total"
        }

        else -> stringResource(Res.string.chat_usage_unknown, used) + total
    }
}
