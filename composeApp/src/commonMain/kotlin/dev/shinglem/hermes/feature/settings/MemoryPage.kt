package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.MemoryProviderStatusDto

/**
 * 设置 → 智能体 →「记忆」。
 *
 * 本版只支持**内置记忆**（`MEMORY.md` / `USER.md`，按 profile 隔离）。
 * 外部 provider（`plugins/memory/<name>/`）只做只读状态展示：启用它们要装 CLI 或订阅云服务，
 * 与「插件体系」一起规划，这里不提供入口 —— 见 CHANGELOG 的「未发布」段。
 */
@Composable
fun MemoryPage(
    state: MemoryUiState,
    profileKey: String?,
    profileScope: ProfileScope? = null,
    onRefresh: () -> Unit,
    onAskReset: (String) -> Unit,
    onConfirmReset: () -> Unit,
    onDismissReset: () -> Unit,
    onConfigEdit: (String, String) -> Unit = { _, _ -> },
    onConfigSave: () -> Unit = {},
    onConfigDiscard: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onFileEdit: (String) -> Unit = {},
    onFileSave: () -> Unit = {},
    onFileClose: () -> Unit = {},
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(profileKey) {
        if (state.status == null) onRefresh()
    }
    var providersExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        MaintenanceHeader(
            title = "记忆",
            subtitle = "记忆写进 agent 的长期上下文。本版只用内置记忆（MEMORY.md / USER.md），" +
                "外部记忆 provider 的启用随「插件功能」统一规划。",
        )
        ProfileScopeChips(profileScope)
        Spacer(Modifier.height(14.dp))

        val status = state.status
        if (status == null) {
            Text(
                if (state.loading) "正在读取…" else "尚未读取到记忆状态",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("重新读取") }
            return@Column
        }

        Text("当前记忆", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (status.active.isBlank()) "内置记忆（MEMORY.md / USER.md）" else "外部 provider：${status.active}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (status.active.isNotBlank()) {
            Text(
                "外部 provider 生效时内置文件不再参与对话；下面的大小与重置只影响这两个文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("内置层", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "MEMORY.md 与 USER.md 是 Hermes 自己的记忆，独立于外部 provider；两者默认都会载入会话。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        MEMORY_FIELDS.forEach { spec ->
            when (spec.kind) {
                ConfigFieldKind.BOOLEAN -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                        spec.hint?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = state.configValue(spec).equals("true", ignoreCase = true),
                        onCheckedChange = { onConfigEdit(spec.path, it.toString()) },
                    )
                }

                ConfigFieldKind.NUMBER -> Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    OutlinedTextField(
                        value = state.configValue(spec),
                        onValueChange = { text -> onConfigEdit(spec.path, text.filter { it.isDigit() }) },
                        label = { Text(spec.label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    spec.hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }
        }
        if (state.configEdits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "有未保存的改动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onConfigSave, enabled = !state.configSaving) {
                    Text(if (state.configSaving) "保存中…" else "保存")
                }
                TextButton(onClick = onConfigDiscard) { Text("取消改动") }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("内置记忆文件", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val memoryBytes = status.builtinFiles["memory"] ?: 0L
        val userBytes = status.builtinFiles["user"] ?: 0L
        FileLine("MEMORY.md", formatBytes(memoryBytes), onClick = { onOpenFile("memory") })
        FileLine("USER.md", formatBytes(userBytes), onClick = { onOpenFile("user") })
        Spacer(Modifier.height(4.dp))
        Text(
            "重置会直接删除文件，不能撤销；provider 侧的记忆不受这里影响。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onAskReset("memory") },
                enabled = memoryBytes > 0 && state.busy == null,
            ) { Text("重置 MEMORY.md") }
            OutlinedButton(
                onClick = { onAskReset("user") },
                enabled = userBytes > 0 && state.busy == null,
            ) { Text("重置 USER.md") }
            OutlinedButton(
                onClick = { onAskReset("all") },
                enabled = (memoryBytes + userBytes) > 0 && state.busy == null,
            ) { Text("全部重置") }
        }

        MaintenanceStatus(state.notice, state.error, onDismissStatus)

        if (state.editingFile != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                "编辑 ${if (state.editingFile == "user") "USER.md" else "MEMORY.md"}",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(2.dp))
            MonoLine(state.filePath)
            Spacer(Modifier.height(6.dp))
            if (state.fileLoading) {
                Text(
                    "正在读取…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = state.fileText,
                    onValueChange = onFileEdit,
                    minLines = 10,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.fileText.length} 字符 · 保存会直接覆盖文件（后端原子替换）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onFileSave, enabled = !state.fileSaving) {
                        Text(if (state.fileSaving) "保存中…" else "保存")
                    }
                    TextButton(onClick = onFileClose) { Text("关闭") }
                }
            }
        }

        state.busy?.let {
            Spacer(Modifier.height(10.dp))
            Text("正在执行…", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(12.dp))
        Row {
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }

        if (status.providers.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { providersExpanded = !providersExpanded },
            ) {
                Text(
                    "外部记忆 provider（${status.providers.size}）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (providersExpanded) "收起" else "查看状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (providersExpanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "本版不提供启用入口：byterover 需要额外安装 brv CLI，retaindb 需要订阅云服务。" +
                        "两者的启用与依赖安装随「插件功能」一起规划，这里只显示当前状态。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.providers.forEach { provider -> MemoryProviderInfo(provider) }
            }
        }
    }

    state.pendingReset?.let { target ->
        val what = when (target) {
            "memory" -> "MEMORY.md"
            "user" -> "USER.md"
            else -> "MEMORY.md 与 USER.md"
        }
        MaintenanceConfirm(
            title = "重置记忆",
            text = "将删除 $what，且不能撤销。确定继续？",
            confirmLabel = "删除",
            onConfirm = onConfirmReset,
            onDismiss = onDismissReset,
        )
    }
}

/** 一个内置记忆文件：名字 + 体积 + 「查看/编辑」。 */
@Composable
private fun FileLine(name: String, size: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            size,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) { Text("查看/编辑") }
    }
}

/** 外部 provider 的只读状态（名字 + 状态 + 缺什么）。 */@Composable
private fun MemoryProviderInfo(provider: MemoryProviderStatusDto) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(provider.name, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                memoryStatusLabel(provider.status),
                style = MaterialTheme.typography.labelSmall,
                color = when (provider.status) {
                    "ready" -> MaterialTheme.colorScheme.primary
                    "needs_config" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
        if (provider.description.isNotBlank()) {
            Text(
                provider.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        memoryProviderHints(provider).forEach { hint ->
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}