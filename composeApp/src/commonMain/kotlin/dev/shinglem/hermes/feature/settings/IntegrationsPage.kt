package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置 → 集成 →「外部集成」：shell 钩子 + 配对白名单。
 *
 * 钩子在后端执行本机脚本（`event` + `command` 是它的身份），配对决定哪些远程管理端能连。
 * 两者都只在**当前配置档案**里生效。
 */
@Composable
fun IntegrationsPage(
    state: IntegrationUiState,
    profileScope: ProfileScope? = null,
    onRefresh: () -> Unit,
    onEventChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onMatcherChange: (String) -> Unit,
    onAddHook: () -> Unit,
    onDeleteHook: (String, String) -> Unit,
    onApprove: (String, String) -> Unit,
    onRevoke: (String, String) -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        if (state.hooks == null) onRefresh()
    }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        MaintenanceHeader(
            title = "外部集成",
            subtitle = "shell 钩子在后端执行本机脚本；配对决定哪些远程管理端能连上来。两者都只作用于当前配置档案。",
        )
        ProfileScopeChips(profileScope)
        Spacer(Modifier.height(14.dp))

        Text("shell 钩子", style = MaterialTheme.typography.labelMedium)
        Text(
            "钩子在事件发生时运行命令（如 `pre_tool`）。新增时会同时写入同意白名单，" +
                "没批准过的钩子不会触发。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        val hooks = state.hooks
        if (hooks == null) {
            Text(
                if (state.loading) "正在读取…" else "未读取到钩子",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (hooks.hooks.isEmpty()) {
            Text(
                "还没有配置钩子。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            hooks.hooks.forEach { hook ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(hook.event, style = MaterialTheme.typography.bodySmall)
                        MonoLine(hook.command, maxLines = 2)
                        Text(
                            buildString {
                                append(if (hook.allowed) "已批准" else "未批准")
                                append(if (hook.executable) " · 可执行" else " · 不可执行")
                                hook.timeout?.let { append(" · 超时 ${it}s") }
                                hook.matcher?.takeIf { it.isNotBlank() }?.let { append(" · matcher=$it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hook.allowed && hook.executable) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    TextButton(
                        onClick = { onDeleteHook(hook.event, hook.command) },
                        enabled = state.busy == null,
                    ) { Text("删除") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("新增钩子", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = state.newEvent,
            onValueChange = onEventChange,
            label = { Text("事件名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        hooks?.validEvents?.takeIf { it.isNotEmpty() }?.let { events ->
            Text(
                "合法事件：${events.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.newCommand,
            onValueChange = onCommandChange,
            label = { Text("命令（脚本路径或命令行）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.newMatcher,
            onValueChange = onMatcherChange,
            label = { Text("matcher（可选，如工具名）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onAddHook,
            enabled = state.busy == null && state.newEvent.isNotBlank() && state.newCommand.isNotBlank(),
        ) { Text("添加并批准") }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))

        Text("配对白名单", style = MaterialTheme.typography.labelMedium)
        Text(
            "待批准请求来自远程管理端；批准后该用户可以用对应平台连接。" +
                "配对码（code）由用户转述，这里按 request_id 批准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        val pairing = state.pairing
        if (pairing == null) {
            Text(
                if (state.loading) "正在读取…" else "未读取到配对状态（该后端可能未启用消息平台）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (pairing.pending.isEmpty()) {
                Text(
                    "没有待批准请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("待批准（${pairing.pending.size}）", style = MaterialTheme.typography.labelSmall)
                pairing.pending.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${item.platform} · ${item.userName.ifBlank { item.userId }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            MonoLine(item.requestId.ifBlank { "(旧条目无 request_id，用配对码在 CLI 批准)" })
                        }
                        OutlinedButton(
                            onClick = { onApprove(item.platform, item.requestId) },
                            enabled = state.busy == null && item.requestId.isNotBlank(),
                            modifier = Modifier.widthIn(min = 72.dp),
                        ) { Text("批准") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("已批准（${pairing.approved.size}）", style = MaterialTheme.typography.labelSmall)
            if (pairing.approved.isEmpty()) {
                Text(
                    "还没有已批准用户。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                pairing.approved.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${item.platform} · ${item.userName.ifBlank { item.userId }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            MonoLine(item.userId)
                        }
                        TextButton(
                            onClick = { onRevoke(item.platform, item.userId) },
                            enabled = state.busy == null,
                        ) { Text("撤销") }
                    }
                }
            }
        }

        MaintenanceStatus(state.notice, state.error, onDismissStatus)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }
    }
}
