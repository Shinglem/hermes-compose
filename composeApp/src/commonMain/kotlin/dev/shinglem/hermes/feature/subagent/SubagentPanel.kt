package dev.shinglem.hermes.feature.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.common_close
import dev.shinglem.hermes.resources.subagent_empty
import dev.shinglem.hermes.resources.subagent_interrupt
import dev.shinglem.hermes.resources.subagent_loading
import dev.shinglem.hermes.resources.subagent_refresh
import dev.shinglem.hermes.resources.subagent_showing
import dev.shinglem.hermes.resources.subagent_steer
import dev.shinglem.hermes.resources.subagent_steer_cancel
import dev.shinglem.hermes.resources.subagent_steer_hint
import dev.shinglem.hermes.resources.subagent_steer_send
import dev.shinglem.hermes.resources.subagent_title
import dev.shinglem.hermes.resources.subagent_tools
import dev.shinglem.hermes.resources.subagent_tail_empty
import dev.shinglem.hermes.resources.subagent_unavailable
import dev.shinglem.hermes.ui.component.ErrorBanner
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 子代理面板（模态）。
 *
 * 为什么是模态而不是右栏：子代理是"看一眼进展"的临时视图，
 * 常驻会挤掉消息宽度，而打开时打断一下是可接受的。
 */
@Composable
fun SubagentPanel(
    state: SubagentState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onLoadTail: (String) -> Unit,
    onSteer: (String, String) -> Unit,
    onInterrupt: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(680.dp).heightIn(min = 320.dp, max = 640.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.subagent_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.subagent_showing, state.entries.size, state.running),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = AppIcons.Refresh,
                            contentDescription = stringResource(Res.string.subagent_refresh),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = AppIcons.ArrowBack,
                            contentDescription = stringResource(Res.string.common_close),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                state.error?.let { error ->
                    ErrorBanner(
                        error = error,
                        onRetry = onRefresh,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                state.notice?.let { notice ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissNotice) {
                            Text(stringResource(Res.string.common_close))
                        }
                    }
                }

                when {
                    state.unavailable -> Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.subagent_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    state.loading && state.entries.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(Res.string.subagent_loading),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    state.entries.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.subagent_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.entries, key = { it.id }) { entry ->
                            SubagentCard(
                                entry = entry,
                                steering = state.steeringId == entry.id,
                                onLoadTail = { onLoadTail(entry.id) },
                                onSteer = { text -> onSteer(entry.id, text) },
                                onInterrupt = { onInterrupt(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentCard(
    entry: SubagentEntry,
    steering: Boolean,
    onLoadTail: () -> Unit,
    onSteer: (String) -> Unit,
    onInterrupt: () -> Unit,
) {
    var steerOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (entry.status == SubagentViewModel.STATUS_RUNNING) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            RoundedCornerShape(4.dp),
                        ),
                )
                Text(
                    text = entry.goal.ifBlank { entry.id },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            val toolsText = stringResource(Res.string.subagent_tools, entry.toolCount)
            Text(
                text = buildString {
                    append(entry.status ?: "—")
                    append(" · ")
                    append(toolsText)
                    entry.lastTool?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    entry.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entry.tail.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.heightIn(max = 160.dp)) {
                        SelectionContainer {
                            Text(
                                text = entry.tail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.tail.isBlank()) {
                    TextButton(onClick = onLoadTail) {
                        Text(stringResource(Res.string.subagent_tail_empty))
                    }
                }
                TextButton(onClick = { steerOpen = !steerOpen }) {
                    Text(
                        stringResource(
                            if (steerOpen) Res.string.subagent_steer_cancel else Res.string.subagent_steer,
                        ),
                    )
                }
                if (entry.status == SubagentViewModel.STATUS_RUNNING) {
                    TextButton(onClick = onInterrupt) {
                        Text(
                            text = stringResource(Res.string.subagent_interrupt),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (steerOpen) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(stringResource(Res.string.subagent_steer_hint)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = draft.isNotBlank() && !steering,
                        onClick = {
                            onSteer(draft)
                            draft = ""
                            steerOpen = false
                        },
                    ) {
                        if (steering) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(Res.string.subagent_steer_send))
                        }
                    }
                }
            }
        }
    }
}
