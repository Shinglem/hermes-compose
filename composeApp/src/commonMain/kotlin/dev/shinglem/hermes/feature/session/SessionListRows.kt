package dev.shinglem.hermes.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.time.SessionGroup
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.core.time.TimeLabel
import dev.shinglem.hermes.domain.model.SessionSummary
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.sessions_archive
import dev.shinglem.hermes.resources.sessions_delete
import dev.shinglem.hermes.resources.sessions_duplicate
import dev.shinglem.hermes.resources.sessions_export_json
import dev.shinglem.hermes.resources.sessions_export_markdown
import dev.shinglem.hermes.resources.sessions_group_pinned
import dev.shinglem.hermes.resources.sessions_more
import dev.shinglem.hermes.resources.sessions_pin
import dev.shinglem.hermes.resources.sessions_rename
import dev.shinglem.hermes.resources.sessions_unpin
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource
internal val SessionRow.kind: String
    get() = when (this) {
        is SessionRow.Header -> "header"
        is SessionRow.Item -> "session"
    }

internal fun rowKey(row: SessionRow): String = when (row) {
    is SessionRow.Header -> "header-${row.group?.name ?: "pinned"}"
    is SessionRow.Item -> "session-${row.session.id}"
}

@Composable
internal fun SessionGroupHeader(
    group: SessionGroup?,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = group?.label() ?: stringResource(Res.string.sessions_group_pinned),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (collapsed) AppIcons.ExpandMore else AppIcons.ExpandLess,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SessionRowItem(
    session: SessionSummary,
    selected: Boolean,
    pinned: Boolean,
    busy: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onExportJson: () -> Unit,
    onExportMarkdown: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(background, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .hoverable(interaction)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                // 运行态点：后端有 live 会话（正在生成或排着队）。
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (pinned) {
                Icon(
                    imageVector = AppIcons.Pin,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (hovered || menuOpen) {
                Box {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.More,
                                contentDescription = stringResource(Res.string.sessions_more),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sessions_rename)) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (pinned) Res.string.sessions_unpin else Res.string.sessions_pin,
                                    ),
                                )
                            },
                            onClick = { menuOpen = false; onTogglePin() },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sessions_duplicate)) },
                            onClick = { menuOpen = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sessions_export_json)) },
                            onClick = { menuOpen = false; onExportJson() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sessions_export_markdown)) },
                            onClick = { menuOpen = false; onExportMarkdown() },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sessions_archive)) },
                            onClick = { menuOpen = false; onArchive() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(Res.string.sessions_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            } else {
                Text(
                    text = TimeFormat.labelOf(session.startedAt).text(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (session.preview.isNotBlank() && session.preview != session.title) {
            Text(
                text = session.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

