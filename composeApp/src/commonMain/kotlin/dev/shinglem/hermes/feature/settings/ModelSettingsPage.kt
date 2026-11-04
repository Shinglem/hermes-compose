package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 模型配置 · 主模型页。
 *
 * 「应用」写的是 profile 的默认模型（后端 config.yaml），只对**新会话**生效 ——
 * 页面里必须说清楚，否则用户会以为当前会话也该跟着变。
 */
@Composable
fun MainModelPage(
    state: ModelSettingsState,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onContextChange: (String) -> Unit,
    onSaveContext: () -> Unit,
    onApply: () -> Unit,
    onConfirmPending: () -> Unit,
    onDismissPending: () -> Unit,
    onReloadCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.info.valueOrNull

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text("主模型", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "保存后写入后端 config.yaml，对**新会话**生效；正在进行的会话不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        when (val load = state.info) {
            is Loadable.Idle, is Loadable.Loading -> LoadingRow()
            is Loadable.Failed -> Text(
                text = load.error.toNotice(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is Loadable.Ready -> {
                KeyValue("当前模型", load.value.label.ifBlank { "（未设置）" })
                if (load.value.effectiveContextLength > 0) {
                    val auto = load.value.autoContextLength
                    val configured = load.value.configContextLength
                    KeyValue(
                        "上下文",
                        if (configured > 0) {
                            "${load.value.effectiveContextLength}（手动覆盖；自动探测 $auto）"
                        } else {
                            "${load.value.effectiveContextLength}（自动探测）"
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        val catalog = state.catalog
        if (catalog is Loadable.Failed) {
            Text(
                text = catalog.error.toNotice(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onReloadCatalog) { Text("重新读取模型目录") }
        } else if (state.providerOptions.isEmpty()) {
            LoadingRow("正在读取模型目录…（首次会探各 provider，可能要十几秒）")
        } else {
            PickerRow(
                label = "提供方",
                value = state.providerOptions.firstOrNull { it.first == state.provider }?.second
                    ?: if (state.provider.isBlank()) "（未选择）" else state.provider,
                options = state.providerOptions,
                busy = state.busyKey != null,
                onSelect = onProvider,
            )
            Spacer(Modifier.height(10.dp))
            val models = state.modelsOf(state.provider).map { it.model to it.model }
            PickerRow(
                label = "模型",
                value = state.model.ifBlank { "（未选择）" },
                options = models,
                busy = state.busyKey != null || models.isEmpty(),
                onSelect = onModel,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, enabled = state.busyKey == null && state.selectionDirty) {
                    Text(if (state.busyKey == "main") "正在应用…" else "应用")
                }
                if (info != null && state.selectionDirty) {
                    Text(
                        "选中 ${state.provider} / ${state.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Text("上下文长度覆盖", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "留空表示使用后端自动探测的值；填数字强制覆盖（0 等同留空）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.contextDraft,
                onValueChange = onContextChange,
                singleLine = true,
                placeholder = { Text("自动") },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            )
            Button(onClick = onSaveContext, enabled = state.busyKey == null) {
                Text(if (state.busyKey == "context") "正在保存…" else "保存")
            }
        }

        state.notice?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
    }

    state.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissPending,
            title = { Text("确认使用较贵的模型？") },
            text = { Text(pending.message) },
            confirmButton = { TextButton(onClick = onConfirmPending) { Text("仍然使用") } },
            dismissButton = { TextButton(onClick = onDismissPending) { Text("取消") } },
        )
    }
}

/** 模型配置 · 备用模型页：主模型不可用时的回退顺序。 */
@Composable
fun FallbackModelPage(
    state: ModelSettingsState,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.fallback.valueOrNull
    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text("备用模型", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "主模型额度耗尽或不可用时，按这里的顺序依次回退。「添加」用的是主模型页当前选中的 provider / 模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAdd, enabled = state.busyKey == null) {
                Text("添加 ${state.provider}/${state.model}".trimEnd('/'))
            }
            OutlinedButton(onClick = onSave, enabled = state.busyKey == null && entries != null) {
                Text(if (state.busyKey == "fallback") "正在保存…" else "保存")
            }
            TextButton(onClick = onRefresh, enabled = state.busyKey == null) { Text("重新读取") }
        }

        Spacer(Modifier.height(12.dp))
        when (val load = state.fallback) {
            is Loadable.Idle, is Loadable.Loading -> LoadingRow()
            is Loadable.Failed -> Text(
                load.error.toNotice(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is Loadable.Ready -> {
                if (load.value.isEmpty()) {
                    Text(
                        "回退链为空：主模型不可用时直接失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    load.value.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemove(index) }, enabled = state.busyKey == null) { Text("移除") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        state.notice?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 模型配置 · 辅助模型页：任务槽位列表 + 全部重置。 */
@Composable
fun AuxModelPage(
    state: ModelSettingsState,
    onAssign: (AuxTaskDto) -> Unit,
    onReset: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aux = state.aux
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("辅助模型", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "压缩、摘要、检索等后台任务用的模型。默认跟随主模型；钉住某个槽位后它不再随主模型变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRefresh, enabled = state.busyKey == null) { Text("刷新") }
                OutlinedButton(onClick = onReset, enabled = state.busyKey == null) {
                    Text(if (state.busyKey == "aux-reset") "正在重置…" else "全部重置")
                }
                val main = aux.valueOrNull?.main
                if (main != null) {
                    Text(
                        "主模型：${main.provider} / ${main.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (aux) {
            is Loadable.Idle, is Loadable.Loading -> LoadingRow()
            is Loadable.Failed -> Column(modifier = Modifier.padding(20.dp)) {
                Text(aux.error.toNotice(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh) { Text("重试") }
            }

            is Loadable.Ready -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(aux.value.tasks, key = { it.task }) { task ->
                    AuxTaskRow(
                        task = task,
                        busy = state.busyKey == "aux:${task.task}",
                        onAssign = { onAssign(task) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuxTaskRow(task: AuxTaskDto, busy: Boolean, onAssign: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task.label.ifBlank { task.task }, style = MaterialTheme.typography.bodyMedium)
                Badge(task.task)
                if (task.isAuto) Badge("跟随主模型")
                if (task.inheritFrom != null) Badge("继承 ${task.inheritFrom}")
                if (task.localEndpoint) Badge("本地端点")
            }
            val detail = when {
                task.isAuto && task.effective != null ->
                    "生效：${task.effective.provider} / ${task.effective.model}"

                task.isAuto -> "未钉住"
                task.model.isNotBlank() -> "${task.provider} / ${task.model}"
                else -> task.provider
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.hint.isNotBlank()) {
                Text(
                    task.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onAssign, enabled = !task.isAuto || task.effective != null) { Text("设为当前模型") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Box {
            Surface(
                onClick = { open = true },
                enabled = !busy,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                    Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(modifier = Modifier.widthIn(min = 260.dp, max = 420.dp).heightIn(max = 360.dp)) {
                    options.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            onClick = {
                                open = false
                                onSelect(id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LoadingRow(text: String = "正在读取…") {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Unspecified)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
