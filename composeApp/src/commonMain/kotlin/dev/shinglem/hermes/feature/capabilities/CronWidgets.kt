package dev.shinglem.hermes.feature.capabilities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.shinglem.hermes.core.contract.BlueprintDto
import dev.shinglem.hermes.core.contract.BlueprintFieldDto
import dev.shinglem.hermes.core.contract.CronRunDto
import dev.shinglem.hermes.core.contract.CronRunOutcome
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capability_cron_blueprint
import dev.shinglem.hermes.resources.capability_cron_cancel
import dev.shinglem.hermes.resources.capability_cron_create
import dev.shinglem.hermes.resources.capability_cron_status_completed
import dev.shinglem.hermes.resources.capability_cron_status_failed
import dev.shinglem.hermes.resources.capability_cron_status_running
import dev.shinglem.hermes.resources.capability_cron_run_duration
import org.jetbrains.compose.resources.stringResource
/** 蓝图下拉：列出后端给的自动化模板，选中即展开槽位表单。 */
@Composable
internal fun BlueprintMenu(blueprints: List<BlueprintDto>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(Res.string.capability_cron_blueprint))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            blueprints.forEach { blueprint ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(blueprint.title.ifBlank { blueprint.key })
                            if (blueprint.description.isNotBlank()) {
                                Text(
                                    text = blueprint.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = {
                        onPick(blueprint.key)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 蓝图表单：字段完全由后端 `fields[]` 驱动（time / enum / text），
 * 所以后端加模板不用改客户端。
 */
@Composable
internal fun BlueprintForm(
    draft: BlueprintDraft,
    blueprint: BlueprintDto?,
    busy: Boolean,
    onChange: (String, String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = draft.title.ifBlank { draft.key }, style = MaterialTheme.typography.titleSmall)
            blueprint?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            blueprint?.fields.orEmpty().forEach { field ->
                val value = draft.values[field.name].orEmpty()
                when {
                    field.options.isNotEmpty() -> EnumField(
                        label = field.label.ifBlank { field.name },
                        options = field.options,
                        selected = value,
                        onSelect = { onChange(field.name, it) },
                    )

                    else -> OutlinedTextField(
                        value = value,
                        onValueChange = { onChange(field.name, it) },
                        label = { Text(field.label.ifBlank { field.name }) },
                        supportingText = field.help.takeIf { it.isNotBlank() }?.let { help ->
                            { Text(help, style = MaterialTheme.typography.labelSmall) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(Res.string.capability_cron_cancel))
                }
                Button(onClick = onSave, enabled = !busy && draft.canSave) {
                    Text(stringResource(Res.string.capability_cron_create))
                }
            }
        }
    }
}

/** `type=enum` 的槽位：用下拉而不是自由输入，避免填出后端不认的值。 */
@Composable
internal fun EnumField(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.ifBlank { options.firstOrNull().orEmpty() }, style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** 一条运行记录：状态 chip + 开始时间 + 时长（这个端点没有 status，按 ended_at 推导）。 */
@Composable
internal fun RunRow(run: CronRunDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunOutcomeChip(run.outcome)
        val started = run.startedAt
        Text(
            text = if (started == null) {
                run.id
            } else {
                TimeFormat.dateTimeText(TimeFormat.fromEpochSeconds(started))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        run.durationSeconds?.let { seconds ->
            Text(
                text = stringResource(Res.string.capability_cron_run_duration, seconds.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        run.handoffError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun RunOutcomeChip(outcome: CronRunOutcome) {
    val (label, color) = when (outcome) {
        CronRunOutcome.RUNNING -> stringResource(Res.string.capability_cron_status_running) to
            MaterialTheme.colorScheme.onSurfaceVariant
        CronRunOutcome.COMPLETED -> stringResource(Res.string.capability_cron_status_completed) to
            MaterialTheme.colorScheme.primary
        CronRunOutcome.FAILED -> stringResource(Res.string.capability_cron_status_failed) to
            MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
internal fun RunStatusChip(status: String) {
    val label = when (status) {
        "running", "claimed" -> stringResource(Res.string.capability_cron_status_running)
        "completed" -> stringResource(Res.string.capability_cron_status_completed)
        "failed" -> stringResource(Res.string.capability_cron_status_failed)
        else -> status
    }
    val color = when (status) {
        "completed" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
