package dev.shinglem.hermes.feature.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import dev.shinglem.hermes.feature.session.SessionSummary
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.palette_empty
import dev.shinglem.hermes.resources.palette_hint
import dev.shinglem.hermes.resources.palette_sessions

/** 命令面板里的一条动作。 */
data class CommandItem(
    val id: String,
    val title: String,
    val hint: String? = null,
    val icon: ImageVector? = null,
    val action: () -> Unit,
)

/**
 * Ctrl+K 命令面板。
 *
 * 纯本地：会话（已加载的列表 + 标题过滤）、设置页跳转、常用动作。
 * **不接**后端全文搜索 —— 那是会话侧栏「搜消息」的职责，别让两个入口抢同一个后端请求。
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    sessions: List<SessionSummary>,
    commands: List<CommandItem>,
    onSelectSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val needle = query.trim()
    val matchedCommands = remember(needle, commands) {
        if (needle.isEmpty()) commands
        else commands.filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.hint?.contains(needle, ignoreCase = true) == true
        }
    }
    val matchedSessions = remember(needle, sessions) {
        if (needle.isEmpty()) sessions.take(8)
        else sessions.filter {
            it.displayTitle.contains(needle, ignoreCase = true) ||
                it.preview.contains(needle, ignoreCase = true)
        }.take(12)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.width(560.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(Res.string.palette_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }

                                Key.Enter -> {
                                    val command = matchedCommands.firstOrNull()
                                    if (command != null) {
                                        onDismiss()
                                        command.action()
                                        true
                                    } else {
                                        matchedSessions.firstOrNull()?.let { session ->
                                            onDismiss()
                                            onSelectSession(session.id)
                                            true
                                        } ?: false
                                    }
                                }

                                else -> false
                            }
                        },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (matchedCommands.isEmpty() && matchedSessions.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.palette_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(matchedCommands, key = { "cmd:${it.id}" }) { command ->
                            PaletteRow(
                                title = command.title,
                                hint = command.hint,
                                icon = command.icon,
                                onClick = {
                                    onDismiss()
                                    command.action()
                                },
                            )
                        }
                        if (matchedSessions.isNotEmpty()) {
                            item(key = "session-header") {
                                Text(
                                    text = stringResource(Res.string.palette_sessions),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(matchedSessions, key = { "session:${it.id}" }) { session ->
                                PaletteRow(
                                    title = session.displayTitle,
                                    hint = session.preview.takeIf { it.isNotBlank() && it != session.title },
                                    icon = null,
                                    onClick = {
                                        onDismiss()
                                        onSelectSession(session.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    title: String,
    hint: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hint?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
