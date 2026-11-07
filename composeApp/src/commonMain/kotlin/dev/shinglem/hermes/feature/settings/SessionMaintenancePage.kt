package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.feature.editors.pickJsonFile
import kotlinx.coroutines.launch

/**
 * 设置 → 应用 →「会话维护」。
 *
 * 只放「整库级别」的读数与清理：单条会话的改名/归档/删除仍在会话列表的 `⋯` 里，
 * 避免同一个动作有两个入口、两种确认文案。
 */
@Composable
fun SessionMaintenancePage(
    state: SessionMaintenanceUiState,
    profileKey: String?,
    profileScope: ProfileScope? = null,
    onRefresh: () -> Unit,
    onAskDeleteEmpty: () -> Unit,
    onConfirmDeleteEmpty: () -> Unit,
    onDismissDeleteEmpty: () -> Unit,
    onPruneDaysChange: (String) -> Unit = {},
    onPreviewPrune: () -> Unit = {},
    onConfirmPrune: () -> Unit = {},
    onDismissPruneReport: () -> Unit = {},
    onImportFile: (String) -> Unit = {},
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmPrune by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(profileKey) {
        if (state.stats == null) onRefresh()
    }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        MaintenanceHeader(
            title = "会话维护",
            subtitle = "整库统计与清理。这里只统计当前 profile 的 session store。",
        )
        ProfileScopeChips(profileScope)
        Spacer(Modifier.height(14.dp))

        val stats = state.stats
        if (stats == null) {
            Text(
                if (state.loading) "正在统计…" else "尚未读取到会话统计",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("会话总数", formatCount(stats.total), Modifier.weight(1f))
                Stat("活跃库", formatCount(stats.activeStore), Modifier.weight(1f))
                Stat("已归档", formatCount(stats.archived), Modifier.weight(1f))
                Stat("消息总数", formatCount(stats.messages), Modifier.weight(1f))
            }
            if (stats.bySource.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("按来源", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                stats.bySource.entries.sortedByDescending { it.value }.forEach { (source, count) ->
                    MonoLine("${source.ifBlank { "(未知)" }}  ${formatCount(count)}")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Text("空会话", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "没有消息、已结束、未归档的会话。正在运行的和已归档的不在其中，" +
                "删除后其子会话会被孤立而不是级联删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        val empty = state.emptyCount
        Text(
            if (empty == null) "数量未知" else "当前有 ${formatCount(empty)} 个空会话",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAskDeleteEmpty,
                enabled = !state.busy && (empty ?: 0) > 0,
            ) { Text(if (state.busy) "正在删除…" else "删除空会话") }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }

        MaintenanceStatus(state.notice, state.error, onDismissStatus)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("按条件清理", style = MaterialTheme.typography.labelMedium)
        Text(
            "删除早于指定天数的**已结束**会话；先「预览」看会删多少，再执行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.pruneDays,
                onValueChange = onPruneDaysChange,
                label = { Text("早于（天）") },
                singleLine = true,
                modifier = Modifier.width(140.dp),
            )
            OutlinedButton(onClick = onPreviewPrune, enabled = !state.pruning) {
                Text(if (state.pruning) "处理中…" else "预览")
            }
            OutlinedButton(
                onClick = { confirmPrune = true },
                enabled = !state.pruning && state.pruneDays.isNotBlank(),
            ) { Text("执行清理") }
        }
        state.pruneReport?.let { report ->
            Spacer(Modifier.height(6.dp))
            Text("预览结果", style = MaterialTheme.typography.labelSmall)
            MonoLine(report.toString(), maxLines = 4)
            TextButton(onClick = onDismissPruneReport) { Text("收起") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("导入会话", style = MaterialTheme.typography.labelMedium)
        Text(
            "选择一个 JSON 文件提交给后端。后端要 `{sessions: [...]}`（会话行）；" +
                "本客户端导出的 Markdown/JSON 是**消息列表**，格式不同，不能直接回灌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    pickJsonFile()?.let(onImportFile)
                }
            },
            enabled = !state.importing,
        ) { Text(if (state.importing) "导入中…" else "选择 JSON 文件…") }

        Spacer(Modifier.height(16.dp))
        Text(
            "会话导出在会话列表的 `⋯` 菜单（JSON / Markdown）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.confirmDeleteEmpty) {
        MaintenanceConfirm(
            title = "删除空会话",
            text = "将删除 ${state.emptyCount ?: 0} 个没有消息的已结束会话。此操作不可撤销。",
            confirmLabel = "删除",
            onConfirm = onConfirmDeleteEmpty,
            onDismiss = onDismissDeleteEmpty,
        )
    }

    if (confirmPrune) {
        MaintenanceConfirm(
            title = "按条件清理",
            text = "将删除早于 ${state.pruneDays} 天的已结束会话。此操作不可撤销，确定继续？",
            confirmLabel = "清理",
            onConfirm = {
                confirmPrune = false
                onConfirmPrune()
            },
            onDismiss = { confirmPrune = false },
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
