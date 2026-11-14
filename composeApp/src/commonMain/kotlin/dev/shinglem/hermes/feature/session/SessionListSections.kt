package dev.shinglem.hermes.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.SessionGroup
import dev.shinglem.hermes.core.time.TimeLabel
import dev.shinglem.hermes.core.time.pad2
import dev.shinglem.hermes.platform.touchDragScroll
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.sessions_archived_restore
import dev.shinglem.hermes.resources.sessions_archived_section
import dev.shinglem.hermes.resources.sessions_group_earlier
import dev.shinglem.hermes.resources.sessions_group_last7
import dev.shinglem.hermes.resources.sessions_group_today
import dev.shinglem.hermes.resources.sessions_group_yesterday
import dev.shinglem.hermes.resources.sessions_loading
import dev.shinglem.hermes.resources.sessions_no_match_title
import dev.shinglem.hermes.resources.sessions_search_content
import dev.shinglem.hermes.resources.sessions_search_content_empty
import dev.shinglem.hermes.resources.sessions_search_content_hint
import dev.shinglem.hermes.resources.sessions_time_yesterday
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.component.ErrorBanner
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource
/**
 * 「已归档」折叠区。
 *
 * 后端列表不返回归档行，所以这里列的是本地快照（归档时记下的标题）；点「恢复」调
 * `session.archive archived=false` 后从快照里删掉，会话重新出现在正常列表里。
 */
@Composable
internal fun ArchivedSection(
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
internal fun ContentSearchSection(
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
internal fun LoadingRows() {
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
internal fun SessionGroup.label(): String = when (this) {
    SessionGroup.TODAY -> stringResource(Res.string.sessions_group_today)
    SessionGroup.YESTERDAY -> stringResource(Res.string.sessions_group_yesterday)
    SessionGroup.LAST_7_DAYS -> stringResource(Res.string.sessions_group_last7)
    SessionGroup.EARLIER -> stringResource(Res.string.sessions_group_earlier)
}

@Composable
internal fun TimeLabel.text(): String = when (this) {
    is TimeLabel.Clock -> "${hour.pad2()}:${minute.pad2()}"
    TimeLabel.Yesterday -> stringResource(Res.string.sessions_time_yesterday)
    is TimeLabel.Date -> "${month.pad2()}-${day.pad2()}"
}
