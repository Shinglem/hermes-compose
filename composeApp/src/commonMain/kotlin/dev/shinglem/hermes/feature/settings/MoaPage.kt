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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.contract.MoaSlotDto
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 模型 · MoA 页：多个顾问模型并行给建议，再由聚合模型产出最终回答。
 *
 * 一个预设 = 一组参考模型 + 一个聚合模型。只有 `enabled` 的那个预设会真正参与运行；
 * 保存提交的是**整份预设表**（后端以此为准，没列出的会被删）。
 */
@Composable
fun MoaModelsPage(
    state: MoaSettingsState,
    modelState: ModelSettingsState,
    onSelectPreset: (String) -> Unit,
    onAddPreset: () -> Unit,
    onDeletePreset: () -> Unit,
    onSetDefault: () -> Unit,
    onSetActive: () -> Unit,
    onPresetChange: (MoaPresetDto) -> Unit,
    onAddReference: () -> Unit,
    onRemoveReference: (Int) -> Unit,
    onReferenceChange: (Int, MoaSlotDto) -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val config = state.config) {
        is Loadable.Idle, is Loadable.Loading -> Row(
            modifier = modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("正在读取 MoA 配置…", style = MaterialTheme.typography.bodySmall)
        }

        is Loadable.Failed -> Column(modifier.fillMaxSize()) {
            EmptyState(
                title = "MoA 配置读取失败",
                body = config.error.toNotice(),
                icon = AppIcons.Error,
                action = { TextButton(onClick = onRefresh) { Text("重试") } },
            )
        }

        is Loadable.Ready -> {
            val preset = state.preset
            Column(
                modifier = modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text("混合代理（MoA）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "参考模型并行给出建议，聚合模型据此产出最终回答。只有启用的预设参与运行，代价约等于顾问数量乘以单模型开销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                PresetSelector(
                    state = state,
                    onSelect = onSelectPreset,
                    onAdd = onAddPreset,
                    onDelete = onDeletePreset,
                    onSetDefault = onSetDefault,
                    onSetActive = onSetActive,
                )

                if (preset == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "这个 profile 还没有可编辑的 MoA 预设。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = preset.enabled,
                        onCheckedChange = { onPresetChange(preset.copy(enabled = it)) },
                        enabled = state.busyKey == null,
                    )
                    Text("启用这个预设", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(10.dp))
                Text("参考模型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                preset.referenceModels.forEachIndexed { index, slot ->
                    SlotEditor(
                        title = "顾问 #${index + 1}",
                        slot = slot,
                        modelState = modelState,
                        busy = state.busyKey == null,
                        onChange = { onReferenceChange(index, it) },
                        onRemove = { onRemoveReference(index) },
                    )
                }
                TextButton(onClick = onAddReference, enabled = state.busyKey == null) { Text("+ 添加顾问") }

                Spacer(Modifier.height(10.dp))
                Text("聚合模型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                SlotEditor(
                    title = "汇总者",
                    slot = preset.aggregator,
                    modelState = modelState,
                    busy = state.busyKey == null,
                    onChange = { onPresetChange(preset.copy(aggregator = it)) },
                )

                Spacer(Modifier.height(14.dp))
                Text("调度", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                PickerLine(
                    label = "顾问节奏",
                    value = fanoutLabel(preset.fanout),
                    options = FANOUT_OPTIONS,
                    busy = state.busyKey != null,
                ) { onPresetChange(preset.copy(fanout = it)) }
                PickerLine(
                    label = "失败披露",
                    value = MOA_POLICIES.firstOrNull { it.first == preset.degradedReferencePolicy }?.second
                        ?: preset.degradedReferencePolicy,
                    options = MOA_POLICIES,
                    busy = state.busyKey != null,
                ) { onPresetChange(preset.copy(degradedReferencePolicy = it)) }

                Spacer(Modifier.height(10.dp))
                AdvancedTuning(preset, state.busyKey == null, onPresetChange)

                Spacer(Modifier.height(14.dp))
                state.presetProblem?.let { problem ->
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onSave,
                        enabled = state.busyKey == null && state.dirty && state.presetProblem == null,
                    ) {
                        Text(if (state.busyKey == "moa") "正在保存…" else "保存")
                    }
                    OutlinedButton(onClick = onRefresh, enabled = state.busyKey == null) { Text("重新读取") }
                    if (state.dirty) {
                        Text(
                            "有未保存的改动",
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
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PresetSelector(
    state: MoaSettingsState,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onSetActive: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Surface(
                onClick = { open = true },
                enabled = state.busyKey == null,
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
                        state.selected.ifBlank { "（无预设）" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(modifier = Modifier.widthIn(min = 200.dp).heightIn(max = 320.dp)) {
                    state.presetNames.forEach { name ->
                        val marker = when (name) {
                            state.activePreset -> "（生效中）"
                            state.defaultPreset -> "（默认）"
                            else -> ""
                        }
                        DropdownMenuItem(
                            text = { Text(name + marker, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                open = false
                                onSelect(name)
                            },
                        )
                    }
                }
            }
        }
        TextButton(onClick = onAdd, enabled = state.busyKey == null) { Text("新增预设") }
        TextButton(onClick = onDelete, enabled = state.busyKey == null && state.presets.size > 1) { Text("删除") }
        TextButton(onClick = onSetDefault, enabled = state.busyKey == null) { Text("设为默认") }
        TextButton(onClick = onSetActive, enabled = state.busyKey == null) { Text("设为生效") }
    }
}

@Composable
private fun SlotEditor(
    title: String,
    slot: MoaSlotDto,
    modelState: ModelSettingsState,
    busy: Boolean,
    onChange: (MoaSlotDto) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
            )
            Picker(
                value = slot.provider.ifBlank { "选择提供方" },
                options = modelState.providerOptions,
                busy = busy,
            ) { provider -> onChange(slot.copy(provider = provider, model = "")) }
            Picker(
                value = slot.model.ifBlank { "选择模型" },
                options = modelState.modelsOf(slot.provider).map { it.model to it.model },
                busy = busy || slot.provider.isBlank(),
            ) { model -> onChange(slot.copy(model = model)) }
            Checkbox(
                checked = slot.enabled,
                onCheckedChange = { onChange(slot.copy(enabled = it)) },
                enabled = !busy,
            )
            Text("启用", style = MaterialTheme.typography.labelSmall)
            if (onRemove != null) {
                TextButton(onClick = onRemove, enabled = !busy) { Text("移除") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Picker(
    value: String,
    options: List<Pair<String, String>>,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            enabled = !busy && options.isNotEmpty(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(modifier = Modifier.widthIn(min = 220.dp, max = 420.dp).heightIn(max = 360.dp)) {
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

@Composable
private fun PickerLine(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Picker(value = value, options = options, busy = busy, onSelect = onSelect)
    }
}

@Composable
private fun AdvancedTuning(
    preset: MoaPresetDto,
    enabled: Boolean,
    onChange: (MoaPresetDto) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起高级参数" else "高级参数（温度 / 超时）")
    }
    if (!expanded) return
    NumberField("顾问温度", preset.referenceTemperature, enabled) {
        onChange(preset.copy(referenceTemperature = it))
    }
    NumberField("聚合温度", preset.aggregatorTemperature, enabled) {
        onChange(preset.copy(aggregatorTemperature = it))
    }
    NumberField("顾问超时（秒）", preset.referenceTimeout, enabled) {
        onChange(preset.copy(referenceTimeout = it))
    }
}

@Composable
private fun NumberField(label: String, value: Double?, enabled: Boolean, onChange: (Double?) -> Unit) {
    // 输入框保留自己的文本：直接绑 value 时，打到一半的 "0." 会解析失败被吃掉。
    var text by remember(value) { mutableStateOf(value?.let(::trimNumber).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() || it == '.' }
            if (text.isBlank()) {
                onChange(null)
            } else {
                text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onChange)
            }
        },
        enabled = enabled,
        singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = { Text("留空 = 用默认值", style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

internal fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** `every_n:N` 在界面上给几个常用档位，已经是别的 N 时保留原值可选项。 */
private val FANOUT_OPTIONS: List<Pair<String, String>> = buildList {
    addAll(MOA_FANOUTS)
    add("every_n:2" to "每 2 次工具迭代")
    add("every_n:3" to "每 3 次工具迭代")
    add("every_n:5" to "每 5 次工具迭代")
}

internal fun fanoutLabel(fanout: String): String =
    FANOUT_OPTIONS.firstOrNull { it.first == fanout }?.second ?: fanout.ifBlank { "user_turn" }
