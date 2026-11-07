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
import androidx.compose.material3.Button
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

/**
 * 设置 → 应用 →「诊断与维护」。
 *
 * 这些动作后端都在，但以前只能开官方客户端或命令行。doctor / 安全审计 / 备份是**后台动作**：
 * 后端回一个句柄，客户端轮询日志尾巴直到结束 —— 所以这里要能看懂"还在跑"和"跑完了退出码非 0"。
 */
@Composable
fun MaintenancePage(
    state: OpsUiState,
    profileKey: String?,
    profileScope: ProfileScope? = null,
    onRefresh: () -> Unit,
    onRun: (MaintenanceAction) -> Unit,
    onAskPrune: () -> Unit,
    onConfirmPrune: () -> Unit,
    onDismissPrune: () -> Unit,
    onToggleCuratorPaused: (Boolean) -> Unit,
    onDownloadBackup: () -> Unit = {},
    onImportArchiveChange: (String) -> Unit = {},
    onImportBackup: () -> Unit = {},
    onDebugShare: () -> Unit = {},
    onDismissDebugUrls: () -> Unit = {},
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmImport by remember { mutableStateOf(false) }
    LaunchedEffect(profileKey) {
        if (state.checkpoints == null && state.curator == null) onRefresh()
    }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        MaintenanceHeader(
            title = "诊断与维护",
            subtitle = "这些命令在后端本机执行，输出写在服务端日志里。运行期间可以切走，回来再看结果。",
        )
        ProfileScopeChips(profileScope)
        Spacer(Modifier.height(14.dp))

        val busy = state.running != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRun(MaintenanceAction.DOCTOR) }, enabled = !busy) { Text("运行诊断") }
            OutlinedButton(onClick = { onRun(MaintenanceAction.SECURITY_AUDIT) }, enabled = !busy) {
                Text("安全审计")
            }
            OutlinedButton(onClick = { onRun(MaintenanceAction.BACKUP) }, enabled = !busy) {
                Text("备份")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onRun(MaintenanceAction.CURATOR_RUN) }, enabled = !busy) {
                Text("立即整理技能")
            }
            OutlinedButton(onClick = { onRun(MaintenanceAction.DUMP) }, enabled = !busy) {
                Text("导出诊断转储")
            }
            OutlinedButton(onClick = onDebugShare, enabled = !state.sharing) {
                Text(if (state.sharing) "生成分享…" else "生成调试分享")
            }
        }

        state.running?.let {
            Spacer(Modifier.height(10.dp))
            Text("正在运行：$it（后台执行，最长等 3 分钟）", style = MaterialTheme.typography.labelSmall)
        }

        MaintenanceStatus(state.notice, state.error, onDismissStatus)

        state.backupArchive?.let { archive ->
            Spacer(Modifier.height(10.dp))
            Text("备份产物", style = MaterialTheme.typography.labelMedium)
            MonoLine(archive)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDownloadBackup, enabled = !state.downloading) {
                    Text(if (state.downloading) "下载中…" else "下载到本机")
                }
            }
            state.downloadedPath?.let {
                Spacer(Modifier.height(4.dp))
                Text("已保存", style = MaterialTheme.typography.labelSmall)
                MonoLine(it)
            }
        }

        if (state.debugUrls.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("调试分享链接", style = MaterialTheme.typography.labelMedium)
            Text(
                "已脱敏日志；链接可能含诊断信息，只发给需要的人。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.debugUrls.forEach { MonoLine(it, maxLines = 2) }
            TextButton(onClick = onDismissDebugUrls) { Text("收起") }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Text("从备份恢复", style = MaterialTheme.typography.labelMedium)
        Text(
            "填服务端可读的备份 zip 路径（备份产物那里显示的就是）。恢复会覆盖当前数据，" +
                "执行前请先确认已退出正在运行的会话。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.importArchive,
            onValueChange = onImportArchiveChange,
            label = { Text("备份 zip 路径") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmImport = true },
                enabled = !state.importing && state.importArchive.isNotBlank(),
            ) { Text(if (state.importing) "恢复中…" else "从备份恢复") }
            state.backupArchive?.let { archive ->
                TextButton(onClick = { onImportArchiveChange(archive) }) { Text("填入刚生成的路径") }
            }
        }

        if (state.log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "日志尾部（最后 ${minOf(state.log.size, LOG_TAIL)} 行）",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            state.log.takeLast(LOG_TAIL).forEach { MonoLine(it) }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Text("技能整理器（curator）", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val curator = state.curator
        if (curator == null) {
            Text(
                "未读取到状态（后端可能未启用 curator）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            !curator.enabled -> "已禁用"
                            curator.paused -> "已暂停"
                            else -> "运行中"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        buildString {
                            curator.intervalHours?.let { append("间隔 ${it} 小时 · ") }
                            append("上次运行 ${curator.lastRunAt ?: "从未"}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = !curator.paused,
                    onCheckedChange = { onToggleCuratorPaused(!it) },
                    enabled = curator.enabled,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Text("回滚检查点", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val checkpoints = state.checkpoints
        if (checkpoints == null) {
            Text(
                if (state.loading) "正在统计…" else "未读取到检查点占用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (checkpoints.sessions.isEmpty()) {
                    "当前没有检查点；`/rollback` 打开时会按需生成。"
                } else {
                    "${checkpoints.sessions.size} 个会话留有快照，共 ${formatBytes(checkpoints.totalBytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (checkpoints.sessions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                checkpoints.sessions.take(8).forEach { item ->
                    MonoLine("${item.session}  ${item.files} 文件  ${formatBytes(item.bytes)}")
                }
                if (checkpoints.sessions.size > 8) {
                    MonoLine("… 另有 ${checkpoints.sessions.size - 8} 个会话")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAskPrune, enabled = !busy) { Text("清理检查点") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }
    }

    if (state.confirmPrune) {
        MaintenanceConfirm(
            title = "清理检查点",
            text = "将删除 `/rollback` 的影子快照，删除后无法再回滚到这些快照。" +
                "当前共占用 ${formatBytes(state.checkpoints?.totalBytes ?: 0)}。确定继续？",
            confirmLabel = "清理",
            onConfirm = onConfirmPrune,
            onDismiss = onDismissPrune,
        )
    }

    if (confirmImport) {
        MaintenanceConfirm(
            title = "从备份恢复",
            text = "将用 ${state.importArchive} 覆盖当前数据（会话库、配置等）。此操作不可撤销，确定继续？",
            confirmLabel = "恢复",
            onConfirm = {
                confirmImport = false
                onImportBackup()
            },
            onDismiss = { confirmImport = false },
        )
    }
}

private const val LOG_TAIL = 25
