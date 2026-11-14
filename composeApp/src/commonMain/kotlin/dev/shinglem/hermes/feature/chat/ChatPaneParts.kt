package dev.shinglem.hermes.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.domain.model.SessionSummary
import dev.shinglem.hermes.resources.detail_messages_count
import dev.shinglem.hermes.resources.detail_source
import dev.shinglem.hermes.resources.detail_started_at
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.chat_backend_still_running
import dev.shinglem.hermes.resources.chat_history_start
import dev.shinglem.hermes.resources.chat_jump_to_latest
import dev.shinglem.hermes.resources.chat_load_older
import dev.shinglem.hermes.resources.chat_load_older_retry
import dev.shinglem.hermes.resources.chat_loading_older
import dev.shinglem.hermes.resources.chat_reconnect
import dev.shinglem.hermes.resources.chat_reload
import dev.shinglem.hermes.resources.chat_subagents
import dev.shinglem.hermes.resources.chat_untitled
import dev.shinglem.hermes.resources.chat_streaming
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource
/** 列表顶部的「加载更早」入口：还有更早的消息时是按钮，加载中显示进度。 */
@Composable
internal fun OlderMessagesRow(state: ChatState, onClick: () -> Unit) {
    val error = state.olderError
    when {
        state.loadingOlder -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(Res.string.chat_loading_older),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error != null -> TextButton(onClick = onClick) {
            Text(stringResource(Res.string.chat_load_older_retry))
        }

        state.truncated -> TextButton(onClick = onClick) {
            Text(stringResource(Res.string.chat_load_older))
        }

        else -> Text(
            text = stringResource(Res.string.chat_history_start),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「回到最新」浮动按钮。独立成函数是为了避开 `ColumnScope` / `BoxScope` 的
 * `AnimatedVisibility` 重载歧义（编译报 DSL_SCOPE_VIOLATION）。
 */
@Composable
internal fun JumpToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(AppIcons.ArrowDown, contentDescription = null) },
            text = { Text(stringResource(Res.string.chat_jump_to_latest)) },
        )
    }
}

@Composable
internal fun ChatHeader(
    summary: SessionSummary,
    messageCount: Int,
    onReload: () -> Unit,
    usage: SessionUsage?,
    usageDetail: String?,
    onShowUsageDetail: () -> Unit,
    onOpenSubagents: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 新建的会话还没拿到后端标题，用占位文案而不是露出 uuid。
                text = if (summary.title.isBlank() && summary.preview.isBlank()) {
                    stringResource(Res.string.chat_untitled)
                } else {
                    summary.displayTitle
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append(stringResource(Res.string.detail_messages_count, messageCount))
                if (summary.source.isNotBlank()) {
                    append(" · ")
                    append(stringResource(Res.string.detail_source, summary.source))
                }
                append(" · ")
                append(stringResource(Res.string.detail_started_at, TimeFormat.dateTimeText(summary.startedAt)))
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ContextUsageBar(
            usage = usage,
            detail = usageDetail,
            onShowDetail = onShowUsageDetail,
            modifier = Modifier.padding(end = 4.dp),
        )
        if (onOpenSubagents != null) {
            IconButton(onClick = onOpenSubagents) {
                Icon(
                    imageVector = AppIcons.Subagent,
                    contentDescription = stringResource(Res.string.chat_subagents),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onReload) {
            Icon(
                imageVector = AppIcons.Refresh,
                contentDescription = stringResource(Res.string.chat_reload),
            )
        }
    }
}

@Composable
internal fun StreamingBlock(state: StreamingState, selectable: Boolean, onReconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.segments.forEach { segment ->
            when (segment) {
                is StreamSegment.Reasoning -> ReasoningBlock(text = segment.text, streaming = true)
                is StreamSegment.Answer -> AssistantAnswer(
                    text = segment.text,
                    selectable = selectable,
                    streaming = true,
                )

                is StreamSegment.Tool -> ToolCallCard(item = segment.item)
            }
        }
        if (state.isEmpty) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(Res.string.chat_streaming),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 静默超过看门狗阈值时给一行状态：让"后端在跑长命令"与"连接已死"可区分（#18）。
        state.stalledSeconds?.let { seconds ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(Res.string.chat_backend_still_running, formatElapsed(seconds)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onReconnect) {
                    Text(
                        text = stringResource(Res.string.chat_reconnect),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
