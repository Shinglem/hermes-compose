package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.error.toNotice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import dev.shinglem.hermes.core.contract.CronJobDto
import dev.shinglem.hermes.core.contract.CronRunDto
import dev.shinglem.hermes.core.contract.CronRunOutcome
import dev.shinglem.hermes.core.contract.DeliveryTargetDto
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capabilities_cron
import dev.shinglem.hermes.resources.capabilities_load_failed
import dev.shinglem.hermes.resources.capabilities_retry
import dev.shinglem.hermes.resources.capability_cron_blueprint
import dev.shinglem.hermes.resources.capability_cron_cancel
import dev.shinglem.hermes.resources.capability_cron_create
import dev.shinglem.hermes.resources.capability_cron_delete
import dev.shinglem.hermes.resources.capability_cron_delete_confirm
import dev.shinglem.hermes.resources.capability_cron_edit
import dev.shinglem.hermes.resources.capability_cron_empty
import dev.shinglem.hermes.resources.capability_cron_empty_body
import dev.shinglem.hermes.resources.capability_cron_field_deliver
import dev.shinglem.hermes.resources.capability_cron_field_name
import dev.shinglem.hermes.resources.capability_cron_field_prompt
import dev.shinglem.hermes.resources.capability_cron_field_schedule
import dev.shinglem.hermes.resources.capability_cron_new
import dev.shinglem.hermes.resources.capability_cron_pause
import dev.shinglem.hermes.resources.capability_cron_paused_tag
import dev.shinglem.hermes.resources.capability_cron_profile_tag
import dev.shinglem.hermes.resources.capability_cron_resume
import dev.shinglem.hermes.resources.capability_cron_runs
import dev.shinglem.hermes.resources.capability_cron_runs_empty
import dev.shinglem.hermes.resources.capability_cron_run_duration
import dev.shinglem.hermes.resources.capability_cron_save_edit
import dev.shinglem.hermes.resources.capability_cron_schedule_hint
import dev.shinglem.hermes.resources.capability_cron_status_completed
import dev.shinglem.hermes.resources.capability_cron_status_failed
import dev.shinglem.hermes.resources.capability_cron_status_running
import dev.shinglem.hermes.resources.capability_cron_subtitle
import dev.shinglem.hermes.resources.capability_cron_trigger
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 定时任务页：列表（cron + 状态 + 开关）+ 新建/编辑表单 + 暂停/恢复/立即运行/删除 + 运行记录。
 *
 * 新建与编辑共用同一个表单（`CronDraft.editingJobId` 区分），编辑走
 * `PUT /api/cron/jobs/{id}`，body 是 `{updates: {...}}`。
 */
@Composable
fun CronPane(
    state: CapabilityState,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onStartBlueprint: (String) -> Unit,
    onBlueprintValue: (String, String) -> Unit,
    onSaveBlueprint: (String, Map<String, String>) -> Unit,
    onCancelBlueprint: () -> Unit,
    onCancelDraft: () -> Unit,
    onDraftChange: ((CronDraft) -> CronDraft) -> Unit,
    onSaveDraft: () -> Unit,
    onSetPaused: (String, Boolean) -> Unit,
    onTrigger: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleRuns: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadable = state.cronJobs
    val jobs = (loadable as? Loadable.Ready)?.value.orEmpty()
    val paused = jobs.count { it.isPaused }

    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = stringResource(Res.string.capabilities_cron),
            subtitle = if (loadable is Loadable.Ready) {
                stringResource(Res.string.capability_cron_subtitle, jobs.size, paused)
            } else {
                ""
            },
            onRefresh = onRefresh,
            action = {
                if (state.blueprints.isNotEmpty()) {
                    BlueprintMenu(
                        blueprints = state.blueprints,
                        onPick = onStartBlueprint,
                    )
                }
                TextButton(onClick = onCreate) {
                    Text(stringResource(Res.string.capability_cron_new))
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        state.blueprintDraft?.let { draft ->
            BlueprintForm(
                draft = draft,
                blueprint = state.blueprints.firstOrNull { it.key == draft.key },
                busy = state.busyKey == "blueprint:${draft.key}",
                onChange = onBlueprintValue,
                onSave = { onSaveBlueprint(draft.key, draft.values) },
                onCancel = onCancelBlueprint,
            )
        }

        state.cronDraft?.let { draft ->
            CronDraftForm(
                draft = draft,
                targets = state.deliveryTargets,
                busy = state.busyKey == (draft.editingJobId ?: "cron:new"),
                onChange = onDraftChange,
                onSave = onSaveDraft,
                onCancel = onCancelDraft,
            )
        }

        when (loadable) {
            is Loadable.Idle, is Loadable.Loading -> LoadingBlock()

            is Loadable.Failed -> {
                val stale = loadable.stale
                if (stale.isNullOrEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        EmptyState(
                            title = stringResource(Res.string.capabilities_load_failed),
                            body = loadable.error.toNotice(),
                            icon = AppIcons.Error,
                            action = {
                                TextButton(onClick = onRefresh) {
                                    Text(stringResource(Res.string.capabilities_retry))
                                }
                            },
                        )
                    }
                } else {
                    RetryBanner(message = loadable.error.toNotice(), onRetry = onRefresh)
                    JobList(stale, state, onSetPaused, onTrigger, onDelete, onToggleRuns, onEdit)
                }
            }

            is Loadable.Ready -> if (loadable.value.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        title = stringResource(Res.string.capability_cron_empty),
                        body = stringResource(Res.string.capability_cron_empty_body),
                    )
                }
            } else {
                JobList(loadable.value, state, onSetPaused, onTrigger, onDelete, onToggleRuns, onEdit)
            }
        }
    }
}

@Composable
private fun JobList(
    jobs: List<CronJobDto>,
    state: CapabilityState,
    onSetPaused: (String, Boolean) -> Unit,
    onTrigger: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleRuns: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(jobs, key = { it.id }) { job ->
            JobRow(
                job = job,
                busy = state.busyKey == job.id,
                expanded = state.expandedJob == job.id,
                runs = state.cronRuns,
                onSetPaused = { onSetPaused(job.id, it) },
                onTrigger = { onTrigger(job.id) },
                onDelete = { onDelete(job.id) },
                onToggleRuns = { onToggleRuns(job.id) },
                onEdit = { onEdit(job.id) },
            )
        }
    }
}

@Composable
private fun JobRow(
    job: CronJobDto,
    busy: Boolean,
    expanded: Boolean,
    runs: Loadable<List<CronRunDto>>,
    onSetPaused: (Boolean) -> Unit,
    onTrigger: () -> Unit,
    onDelete: () -> Unit,
    onToggleRuns: () -> Unit,
    onEdit: () -> Unit,
) {
    var confirmDelete by remember(job.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = job.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (job.isPaused) Tag(text = stringResource(Res.string.capability_cron_paused_tag), emphasise = true)
                    // 列表默认跨 profile 聚合（`profile=all`）：非当前 profile 的任务要标出来源。
                    job.profile?.takeIf { it.isNotBlank() }?.let { name ->
                        Tag(text = stringResource(Res.string.capability_cron_profile_tag, name))
                    }
                    job.latestExecution?.status?.takeIf { it.isNotBlank() }?.let { RunStatusChip(it) }
                }
                Text(
                    text = job.scheduleDisplay.ifBlank { job.deliver },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (job.prompt.isNotBlank()) {
                    Text(
                        text = job.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            // 开关语义：打开 = 运行中，关闭 = 暂停。
            Switch(
                checked = !job.isPaused,
                onCheckedChange = { onSetPaused(!it) },
                enabled = !busy,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTrigger, enabled = !busy) {
                Text(stringResource(Res.string.capability_cron_trigger))
            }
            TextButton(onClick = onEdit, enabled = !busy) {
                Text(stringResource(Res.string.capability_cron_edit))
            }
            TextButton(onClick = onToggleRuns, enabled = !busy) {
                Text(stringResource(Res.string.capability_cron_runs))
            }
            TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                Text(
                    text = stringResource(Res.string.capability_cron_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (confirmDelete) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.capability_cron_delete_confirm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(Res.string.capability_cron_delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.capability_cron_cancel))
                }
            }
        }

        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    when (runs) {
                        is Loadable.Idle, is Loadable.Loading -> LoadingBlock()
                        is Loadable.Failed -> RetryBanner(
                            message = runs.error.toNotice(),
                            onRetry = onToggleRuns,
                        )

                        is Loadable.Ready -> if (runs.value.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.capability_cron_runs_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            runs.value.forEach { run -> RunRow(run) }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 蓝图下拉：列出后端给的自动化模板，选中即展开槽位表单。 */
@Composable
private fun BlueprintMenu(blueprints: List<BlueprintDto>, onPick: (String) -> Unit) {
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
private fun BlueprintForm(
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
private fun EnumField(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
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
private fun RunRow(run: CronRunDto) {
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
private fun RunOutcomeChip(outcome: CronRunOutcome) {
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
private fun RunStatusChip(status: String) {
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

@Composable
private fun CronDraftForm(
    draft: CronDraft,
    targets: List<DeliveryTargetDto>,
    busy: Boolean,
    onChange: ((CronDraft) -> CronDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var deliverOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { value -> onChange { it.copy(name = value) } },
                label = { Text(stringResource(Res.string.capability_cron_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.schedule,
                onValueChange = { value -> onChange { it.copy(schedule = value) } },
                label = { Text(stringResource(Res.string.capability_cron_field_schedule)) },
                supportingText = {
                    Text(
                        text = stringResource(Res.string.capability_cron_schedule_hint),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                isError = !draft.scheduleLooksValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { value -> onChange { it.copy(prompt = value) } },
                label = { Text(stringResource(Res.string.capability_cron_field_prompt)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    TextButton(onClick = { deliverOpen = true }) {
                        Text(
                            "${stringResource(Res.string.capability_cron_field_deliver)}：" +
                                (targets.firstOrNull { it.id == draft.deliver }?.name ?: draft.deliver),
                        )
                    }
                    DropdownMenu(expanded = deliverOpen, onDismissRequest = { deliverOpen = false }) {
                        targets.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target.name) },
                                onClick = {
                                    onChange { it.copy(deliver = target.id) }
                                    deliverOpen = false
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, enabled = !busy) {
                        Text(stringResource(Res.string.capability_cron_cancel))
                    }
                    Button(onClick = onSave, enabled = !busy && draft.canSave) {
                        Text(
                            stringResource(
                                if (draft.isEditing) {
                                    Res.string.capability_cron_save_edit
                                } else {
                                    Res.string.capability_cron_create
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
