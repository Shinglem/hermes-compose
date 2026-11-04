package dev.shinglem.hermes.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.shinglem.hermes.platform.touchDragScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.SessionGroup
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.core.time.TimeLabel
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.common_close
import dev.shinglem.hermes.resources.sessions_archive
import dev.shinglem.hermes.resources.sessions_archive_body
import dev.shinglem.hermes.resources.sessions_archive_cancel
import dev.shinglem.hermes.resources.sessions_archive_ok
import dev.shinglem.hermes.resources.sessions_archive_title
import dev.shinglem.hermes.resources.sessions_archived_restore
import dev.shinglem.hermes.resources.sessions_archived_section
import dev.shinglem.hermes.resources.sessions_creating
import dev.shinglem.hermes.resources.sessions_delete
import dev.shinglem.hermes.resources.sessions_delete_body
import dev.shinglem.hermes.resources.sessions_delete_cancel
import dev.shinglem.hermes.resources.sessions_delete_ok
import dev.shinglem.hermes.resources.sessions_delete_title
import dev.shinglem.hermes.resources.sessions_duplicate
import dev.shinglem.hermes.resources.sessions_empty_body
import dev.shinglem.hermes.resources.sessions_empty_title
import dev.shinglem.hermes.resources.sessions_export_json
import dev.shinglem.hermes.resources.sessions_export_markdown
import dev.shinglem.hermes.resources.sessions_group_earlier
import dev.shinglem.hermes.resources.sessions_group_last7
import dev.shinglem.hermes.resources.sessions_group_pinned
import dev.shinglem.hermes.resources.sessions_group_today
import dev.shinglem.hermes.resources.sessions_group_yesterday
import dev.shinglem.hermes.resources.sessions_loading
import dev.shinglem.hermes.resources.sessions_more
import dev.shinglem.hermes.resources.sessions_new
import dev.shinglem.hermes.resources.sessions_no_match_body
import dev.shinglem.hermes.resources.sessions_no_match_title
import dev.shinglem.hermes.resources.sessions_pin
import dev.shinglem.hermes.resources.sessions_rename
import dev.shinglem.hermes.resources.sessions_rename_cancel
import dev.shinglem.hermes.resources.sessions_rename_hint
import dev.shinglem.hermes.resources.sessions_rename_ok
import dev.shinglem.hermes.resources.sessions_rename_title
import dev.shinglem.hermes.resources.sessions_search_hint
import dev.shinglem.hermes.resources.sessions_search_content
import dev.shinglem.hermes.resources.sessions_search_content_empty
import dev.shinglem.hermes.resources.sessions_search_content_hint
import dev.shinglem.hermes.resources.sessions_time_yesterday
import dev.shinglem.hermes.resources.sessions_unpin
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.component.ErrorBanner
import dev.shinglem.hermes.ui.component.SearchField
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** 会话模式的中栏：搜索、分组列表、空/错/载入态。 */
@Composable
fun SessionListPane(
    state: SessionListState,
    callbacks: SessionListCallbacks,
    modifier: Modifier = Modifier,
) {
    // 分组按「加载时刻」计算并在依赖不变时复用，避免每帧重算。
    val now = remember(state.sessions) { Clock.System.now() }
    // 搜索框有"标题过滤 / 全文检索"两种模式，模式本身是纯 UI 状态，不写进 ViewModel。
    var contentMode by remember { mutableStateOf(false) }
    val rows = remember(state.sessions, state.query, state.collapsedGroups, state.pinned, state.pinnedCollapsed, now) {
        buildSessionRows(
            sessions = state.sessions,
            query = state.query,
            collapsedGroups = state.collapsedGroups,
            now = now,
            pinned = state.pinned,
            pinnedCollapsed = state.pinnedCollapsed,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = if (contentMode) state.contentQuery else state.query,
                onValueChange = { value ->
                    if (contentMode) callbacks.onContentQueryChange(value) else callbacks.onQueryChange(value)
                },
                hint = stringResource(
                    if (contentMode) Res.string.sessions_search_content_hint else Res.string.sessions_search_hint,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    contentMode = !contentMode
                    // 切换模式就清掉另一种的结果，避免两套过滤叠加。
                    if (contentMode) {
                        callbacks.onQueryChange("")
                        callbacks.onContentQueryChange(state.query)
                    } else {
                        callbacks.onContentQueryChange("")
                    }
                },
            ) {
                Icon(
                    imageVector = AppIcons.Search,
                    contentDescription = stringResource(Res.string.sessions_search_content),
                    tint = if (contentMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        state.createError?.let { createError ->
            ErrorBanner(
                error = createError,
                onRetry = callbacks.onDismissCreateError,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        state.rowError?.let { rowError ->
            ErrorBanner(
                error = rowError,
                onRetry = callbacks.onDismissRowError,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        val error = state.error
        if (error != null && state.sessions.isNotEmpty()) {
            ErrorBanner(
                error = error,
                onRetry = callbacks.onRetry,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        state.notice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = callbacks.onDismissNotice) {
                        Text(stringResource(Res.string.common_close))
                    }
                }
            }
        }

        if (state.contentQuery.isNotBlank() || state.contentResults !is Loadable.Idle) {
            // 内容搜索是"另一个列表"，不是标题过滤的补充：见下面的 Box。
            Unit
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                contentMode -> ContentSearchSection(
                    state = state,
                    onSelect = callbacks.onSelect,
                )

                state.load is Loadable.Loading && state.sessions.isEmpty() -> LoadingRows()

                state.load is Loadable.Failed && state.sessions.isEmpty() && error != null -> {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        ErrorBanner(error = error, onRetry = callbacks.onRetry)
                    }
                }

                rows.isEmpty() -> EmptyState(
                    title = stringResource(
                        if (state.query.isBlank()) {
                            Res.string.sessions_empty_title
                        } else {
                            Res.string.sessions_no_match_title
                        },
                    ),
                    body = if (state.query.isBlank()) {
                        stringResource(Res.string.sessions_empty_body, ProductInfo.DISPLAY_NAME)
                    } else {
                        stringResource(Res.string.sessions_no_match_body)
                    },
                    icon = AppIcons.Sessions,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> {
                    // 触屏在桌面被合成为鼠标拖动，LazyColumn 自己不吃；补一条拖动滚动。
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().touchDragScroll(listState),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(
                            items = rows,
                            key = { row -> rowKey(row) },
                            contentType = { row -> row.kind },
                        ) { row ->
                            when (row) {
                                is SessionRow.Header -> SessionGroupHeader(
                                    group = row.group,
                                    collapsed = row.collapsed,
                                    onToggle = {
                                        if (row.group == null) callbacks.onTogglePinnedGroup()
                                        else callbacks.onToggleGroup(row.group)
                                    },
                                )

                                is SessionRow.Item -> SessionRowItem(
                                    session = row.session,
                                    selected = row.session.id == state.selectedId,
                                    pinned = row.session.id in state.pinned,
                                    busy = state.rowBusyId == row.session.id ||
                                        state.duplicatingId == row.session.id,
                                    active = row.session.id in state.activeIds,
                                    onClick = { callbacks.onSelect(row.session.id) },
                                    onRename = { callbacks.onStartRename(row.session.id) },
                                    onTogglePin = { callbacks.onTogglePin(row.session.id) },
                                    onArchive = { callbacks.onAskArchive(row.session.id) },
                                    onDelete = { callbacks.onAskDelete(row.session.id) },
                                    onDuplicate = { callbacks.onDuplicate(row.session.id) },
                                    onExportJson = { callbacks.onExportJson(row.session.id) },
                                    onExportMarkdown = { callbacks.onExportMarkdown(row.session.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.archived.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ArchivedSection(
                items = state.archived,
                expanded = state.archivedExpanded,
                busyId = state.rowBusyId,
                onToggle = callbacks.onToggleArchivedSection,
                onRestore = callbacks.onRestoreArchived,
            )
        }

        // 新建会话固定在列表下方：顶部只留搜索，避免搜索框被按钮挤窄。
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = callbacks.onCreate,
                enabled = !state.creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (state.creating) Res.string.sessions_creating else Res.string.sessions_new,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    state.rename?.let { draft ->
        RenameSessionDialog(
            title = draft.title,
            onTitleChange = callbacks.onRenameTitleChange,
            onConfirm = callbacks.onCommitRename,
            onDismiss = callbacks.onCancelRename,
        )
    }

    state.confirmDeleteId?.let { id ->
        val target = state.sessions.firstOrNull { it.id == id }
        ConfirmDeleteDialog(
            sessionTitle = target?.displayTitle.orEmpty(),
            onConfirm = callbacks.onCommitDelete,
            onDismiss = callbacks.onCancelDelete,
        )
    }

    state.confirmArchiveId?.let { id ->
        val target = state.sessions.firstOrNull { it.id == id }
        ConfirmArchiveDialog(
            sessionTitle = target?.displayTitle.orEmpty(),
            onConfirm = callbacks.onCommitArchive,
            onDismiss = callbacks.onCancelArchive,
        )
    }
}

/** 中栏要用的一整组回调；参数太多时打包，避免 `SessionListPane` 十几个函数平铺。 */
data class SessionListCallbacks(
    val onQueryChange: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onToggleGroup: (SessionGroup) -> Unit,
    val onTogglePinnedGroup: () -> Unit,
    val onRetry: () -> Unit,
    val onCreate: () -> Unit,
    val onDismissCreateError: () -> Unit,
    val onDismissRowError: () -> Unit,
    val onStartRename: (String) -> Unit,
    val onRenameTitleChange: (String) -> Unit,
    val onCommitRename: () -> Unit,
    val onCancelRename: () -> Unit,
    val onTogglePin: (String) -> Unit,
    val onAskArchive: (String) -> Unit,
    val onCommitArchive: () -> Unit,
    val onCancelArchive: () -> Unit,
    val onToggleArchivedSection: () -> Unit,
    val onRestoreArchived: (String) -> Unit,
    val onAskDelete: (String) -> Unit,
    val onCommitDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
    // ── v0.3.0 ──
    val onDuplicate: (String) -> Unit = {},
    val onExportJson: (String) -> Unit = {},
    val onExportMarkdown: (String) -> Unit = {},
    val onContentQueryChange: (String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

private val SessionRow.kind: String
    get() = when (this) {
        is SessionRow.Header -> "header"
        is SessionRow.Item -> "session"
    }

private fun rowKey(row: SessionRow): String = when (row) {
    is SessionRow.Header -> "header-${row.group?.name ?: "pinned"}"
    is SessionRow.Item -> "session-${row.session.id}"
}

@Composable
private fun SessionGroupHeader(
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
private fun SessionRowItem(
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

@Composable
private fun RenameSessionDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sessions_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                singleLine = true,
                label = { Text(stringResource(Res.string.sessions_rename_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = title.isNotBlank()) {
                Text(stringResource(Res.string.sessions_rename_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sessions_rename_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    sessionTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sessions_delete_title)) },
        text = { Text(stringResource(Res.string.sessions_delete_body, sessionTitle)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(Res.string.sessions_delete_ok),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sessions_delete_cancel)) }
        },
    )
}

@Composable
private fun ConfirmArchiveDialog(
    sessionTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sessions_archive_title)) },
        text = { Text(stringResource(Res.string.sessions_archive_body, sessionTitle)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.sessions_archive_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sessions_archive_cancel)) }
        },
    )
}

/**
 * 「已归档」折叠区。
 *
 * 后端列表不返回归档行，所以这里列的是本地快照（归档时记下的标题）；点「恢复」调
 * `session.archive archived=false` 后从快照里删掉，会话重新出现在正常列表里。
 */
@Composable
private fun ArchivedSection(
    items: List<ArchivedSessionRef>,
    expanded: Boolean,
    busyId: String?,
    onToggle: () -> Unit,
    onRestore: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.sessions_archived_section, items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            items.forEach { ref ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ref.title.ifBlank { ref.id },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (busyId == ref.id) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { onRestore(ref.id) }) {
                            Text(
                                text = stringResource(Res.string.sessions_archived_restore),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 内容搜索结果：整块替换会话列表（标题过滤与全文检索不叠加）。 */
@Composable
private fun ContentSearchSection(
    state: SessionListState,
    onSelect: (String) -> Unit,
) {
    when (val results = state.contentResults) {
        Loadable.Idle -> EmptyState(
            title = stringResource(Res.string.sessions_search_content),
            body = stringResource(Res.string.sessions_search_content_hint),
            icon = AppIcons.Search,
            modifier = Modifier.fillMaxSize(),
        )

        is Loadable.Loading -> LoadingRows()

        is Loadable.Failed -> Box(
            Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            ErrorBanner(error = results.error, onRetry = {})
        }

        is Loadable.Ready -> {
            if (results.value.isEmpty()) {
                EmptyState(
                    title = stringResource(Res.string.sessions_no_match_title),
                    body = stringResource(Res.string.sessions_search_content_empty),
                    icon = AppIcons.Search,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val searchListState = rememberLazyListState()
                LazyColumn(
                    state = searchListState,
                    modifier = Modifier.fillMaxSize().touchDragScroll(searchListState),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(items = results.value, key = { it.sessionId + it.snippet.take(24) }) { hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(hit.sessionId) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = hit.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = hit.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRows() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.sessions_loading),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index % 2 == 0) 0.72f else 0.55f)
                    .height(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}

@Composable
private fun SessionGroup.label(): String = when (this) {
    SessionGroup.TODAY -> stringResource(Res.string.sessions_group_today)
    SessionGroup.YESTERDAY -> stringResource(Res.string.sessions_group_yesterday)
    SessionGroup.LAST_7_DAYS -> stringResource(Res.string.sessions_group_last7)
    SessionGroup.EARLIER -> stringResource(Res.string.sessions_group_earlier)
}

@Composable
private fun TimeLabel.text(): String = when (this) {
    is TimeLabel.Clock -> "${hour.pad2()}:${minute.pad2()}"
    TimeLabel.Yesterday -> stringResource(Res.string.sessions_time_yesterday)
    is TimeLabel.Date -> "${month.pad2()}-${day.pad2()}"
}

private fun Int.pad2(): String = toString().padStart(2, '0')
