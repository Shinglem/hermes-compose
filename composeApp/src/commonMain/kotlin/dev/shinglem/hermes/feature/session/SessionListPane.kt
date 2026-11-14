package dev.shinglem.hermes.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.SessionGroup
import dev.shinglem.hermes.platform.touchDragScroll
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.common_close
import dev.shinglem.hermes.resources.sessions_creating
import dev.shinglem.hermes.resources.sessions_empty_body
import dev.shinglem.hermes.resources.sessions_empty_title
import dev.shinglem.hermes.resources.sessions_new
import dev.shinglem.hermes.resources.sessions_no_match_body
import dev.shinglem.hermes.resources.sessions_no_match_title
import dev.shinglem.hermes.resources.sessions_search_content
import dev.shinglem.hermes.resources.sessions_search_content_hint
import dev.shinglem.hermes.resources.sessions_search_hint
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

