package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置 → 应用 →「用量与成本」。
 *
 * 数据来自 `/api/analytics/usage`（按天、按模型、汇总三块）。对话页那条「上下文占用」是**单个会话**
 * 的窗口占用，与这里不是一回事：这里统计的是整个 session store 的历史花费。
 */
@Composable
fun UsagePage(
    state: UsageUiState,
    profileKey: String?,
    profileScope: ProfileScope? = null,
    onRefresh: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(profileKey) {
        if (state.data == null) onRefresh(state.days)
    }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        MaintenanceHeader(
            title = "用量与成本",
            subtitle = "按 session store 里的历史记录统计；辅助模型（压缩、标题、视觉）也计入。" +
                "金额是后端按模型的估算值，实际账单以 provider 为准。",
        )
        ProfileScopeChips(profileScope)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days ->
                FilterChip(
                    selected = state.days == days,
                    onClick = { onRefresh(days) },
                    enabled = !state.loading,
                    label = { Text("最近 $days 天") },
                )
            }
            TextButton(onClick = { onRefresh(state.days) }, enabled = !state.loading) { Text("刷新") }
        }

        val data = state.data
        if (data == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.loading) "正在统计…" else "尚未读取到用量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Spacer(Modifier.height(14.dp))
        val totals = data.totals
        Row {
            StatCell("会话", formatCount(totals.totalSessions), Modifier.weight(1f))
            StatCell("输入", formatCount(totals.totalInput), Modifier.weight(1f))
            StatCell("输出", formatCount(totals.totalOutput), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            StatCell("缓存读", formatCount(totals.totalCacheRead), Modifier.weight(1f))
            StatCell("推理", formatCount(totals.totalReasoning), Modifier.weight(1f))
            StatCell("API 调用", formatCount(totals.totalApiCalls), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            StatCell("估算成本", formatCost(totals.totalEstimatedCost), Modifier.weight(1f))
            StatCell("实际成本", formatCost(totals.totalActualCost), Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }

        if (data.byModel.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text("按模型", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            data.byModel.take(ROW_LIMIT).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        row.model.ifBlank { "(未知)" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${formatCount(row.inputTokens + row.outputTokens)} tok · ${formatCost(row.estimatedCost)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (data.byModel.size > ROW_LIMIT) {
                Text(
                    "另有 ${data.byModel.size - ROW_LIMIT} 个模型未显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (data.daily.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text("按天", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            data.daily.takeLast(ROW_LIMIT).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(row.day, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        "${formatCount(row.inputTokens + row.outputTokens)} tok · " +
                            "${row.sessions} 会话 · ${formatCost(row.estimatedCost)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (data.daily.size > ROW_LIMIT) {
                Text(
                    "只显示最近 $ROW_LIMIT 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val models = state.models
        if (models != null && models.models.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text("模型卡片", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            models.models.take(ROW_LIMIT).forEach { card ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            card.model.ifBlank { "(未知模型)" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            buildString {
                                if (card.provider.isNotBlank()) append("${card.provider} · ")
                                append("${formatCount(card.sessions)} 会话 · ")
                                append("${formatCount(card.apiCalls)} 调用 · ")
                                append("${formatCount(card.toolCalls)} 工具 · ")
                                append("均值 ${formatCount(card.avgTokensPerSession.toLong())} tok/会话")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        formatCost(card.estimatedCost),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        if (data.byTask.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text("辅助任务", style = MaterialTheme.typography.labelMedium)
            Text(
                "压缩、标题、视觉等后台调用单独计费，不计入会话主模型。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            data.byTask.take(ROW_LIMIT).forEach { task ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        task.task.ifBlank { "(未命名)" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${formatCount(task.inputTokens + task.outputTokens)} tok · " +
                            "${formatCount(task.apiCalls)} 调用 · ${formatCost(task.estimatedCost)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

private const val ROW_LIMIT = 12
