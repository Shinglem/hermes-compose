package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.background_task_dismiss
import dev.shinglem.hermes.resources.background_task_exited
import dev.shinglem.hermes.resources.background_task_kill
import dev.shinglem.hermes.resources.background_task_no_output
import dev.shinglem.hermes.resources.background_tasks_running
import dev.shinglem.hermes.resources.background_tasks_title
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * composer 上方的后台任务条（`process.list` 轮询 + `agent.terminal.output` 增量）。
 *
 * 形态对齐官方 composer 状态栈：折叠时一行「N 个后台任务」，展开后每个进程一行 ——
 * 状态点、命令首行、运行时长得看得到，能就地点开输出、终止或收起已结束的行。
 */
@Composable
fun BackgroundTaskBar(
    tasks: List<BackgroundTask>,
    killingTaskId: String?,
    onKill: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    var openTaskId by remember { mutableStateOf<String?>(null) }
    val running = tasks.count { it.running }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.background_tasks_title, tasks.size),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (running > 0) {
                    Text(
                        text = stringResource(Res.string.background_tasks_running, running),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!expanded) return@Column

            tasks.forEach { task ->
                BackgroundTaskRow(
                    task = task,
                    killing = killingTaskId == task.id,
                    outputOpen = openTaskId == task.id,
                    onToggleOutput = { openTaskId = if (openTaskId == task.id) null else task.id },
                    onKill = { onKill(task.id) },
                    onDismiss = { onDismiss(task.id) },
                )
            }
        }
    }
}

@Composable
private fun BackgroundTaskRow(
    task: BackgroundTask,
    killing: Boolean,
    outputOpen: Boolean,
    onToggleOutput: () -> Unit,
    onKill: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                task.running -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
                task.failed -> Icon(
                    imageVector = AppIcons.Error,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleOutput)
                    .padding(vertical = 3.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                task.detailOrNull()?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (task.running) {
                IconButton(
                    onClick = onKill,
                    enabled = !killing,
                    modifier = Modifier.size(28.dp),
                ) {
                    if (killing) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = AppIcons.Stop,
                            contentDescription = stringResource(Res.string.background_task_kill),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = stringResource(Res.string.background_task_dismiss),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (outputOpen) {
            val emptyHint = stringResource(Res.string.background_task_no_output)
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.small,
            ) {
                SelectionContainer {
                    Text(
                        text = task.output.ifBlank { emptyHint },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundTask.detailOrNull(): String? = when {
    running -> uptimeSeconds?.let(::formatUptime) ?: cwd?.takeIf { it.isNotBlank() }
    exitCode != null -> stringResource(Res.string.background_task_exited, exitCode)
    else -> cwd?.takeIf { it.isNotBlank() }
}
