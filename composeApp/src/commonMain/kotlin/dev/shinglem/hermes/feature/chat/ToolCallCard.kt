package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.chat_tool_args
import dev.shinglem.hermes.resources.chat_tool_orphan
import dev.shinglem.hermes.resources.chat_tool_result
import dev.shinglem.hermes.resources.chat_tool_show_all
import dev.shinglem.hermes.resources.chat_tool_show_less
import dev.shinglem.hermes.resources.chat_tool_status_done
import dev.shinglem.hermes.resources.chat_tool_status_failed
import dev.shinglem.hermes.resources.chat_tool_truncated
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 工具调用通用卡片：标题 + 状态 + 折叠的参数与结果。
 *
 * 默认收起，避免几十 KB 的工具输出一次性进入布局（大会话滚动性能的关键）。
 */
@Composable
fun ToolCallCard(
    item: ChatItem.Tool,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    // 结果被截断时允许"看全文"：默认仍只渲染前 MAX_RESULT_CHARS（大会话滚动性能），
    // 用户显式点开才把整段放进布局，且仍受 HARD_MAX_RESULT_CHARS 保护。
    var fullResult by remember(item.id) { mutableStateOf(false) }
    val resultText = item.result.orEmpty()
    val resultTruncated = resultText.length > MAX_RESULT_CHARS
    val shownResult = when {
        !resultTruncated -> resultText
        fullResult -> resultText.take(HARD_MAX_RESULT_CHARS)
        else -> resultText.take(MAX_RESULT_CHARS)
    }
    val statusColor = if (item.failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (item.failed) AppIcons.Error else AppIcons.Tool,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.label ?: item.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.label != null) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (item.failed) Res.string.chat_tool_status_failed else Res.string.chat_tool_status_done,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                Icon(
                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.arguments.isNotBlank()) {
                        CodeSection(
                            title = stringResource(Res.string.chat_tool_args),
                            body = item.arguments.take(MAX_ARGUMENT_CHARS),
                        )
                    }
                    if (resultText.isNotBlank()) {
                        CodeSection(
                            title = stringResource(Res.string.chat_tool_result),
                            body = shownResult,
                        )
                        if (resultTruncated) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.chat_tool_truncated, MAX_RESULT_CHARS),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { fullResult = !fullResult },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (fullResult) {
                                                Res.string.chat_tool_show_less
                                            } else {
                                                Res.string.chat_tool_show_all
                                            },
                                            resultText.length,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    if (!item.pairedWithCall) {
                        Text(
                            text = stringResource(Res.string.chat_tool_orphan),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
                .padding(8.dp),
        )
    }
}

private const val MAX_RESULT_CHARS = 20_000
private const val MAX_ARGUMENT_CHARS = 4_000

/** 「显示全部」的硬上限：再大就不再往布局里塞（单条消息几十万字符会把滚动拖死）。 */
private const val HARD_MAX_RESULT_CHARS = 200_000
