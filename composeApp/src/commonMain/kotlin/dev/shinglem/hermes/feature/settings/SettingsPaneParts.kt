package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.app.OrphanTaskInfo
import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.core.contract.CustomEndpointDto
import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.contract.MoaSlotDto
/**
 * 开机自启的落地状态。三类不一致都要说清楚 —— 否则用户会看到"勾了却没反应"。
 */
internal fun autostartHint(info: AutostartInfo): String? = when {
    info.configured && info.installed -> "已启用：计划任务 ${info.taskName}"
    info.configured && info.pending && !info.elevated ->
        "尚未生效：写计划任务需要管理员权限。请把上面的「要求管理员权限」设为 ask 并重启客户端"
    info.configured && info.pending -> "尚未生效：任务写入失败，详见 logs/startup.log"
    info.configured -> "正在启用：任务 ${info.taskName}"
    info.installed -> "配置已关闭，但系统里仍有任务 ${info.taskName}；以管理员运行后会自动删除"
    else -> null
}

/**
 * 失效的自启任务（便携包挪过位置留下的）。
 *
 * 只读列出 + 逐条删除：本机有个名字带 `Hermes` 前缀但指向 `Netcatty.exe` 的任务，
 * 任何"按前缀批量删"的实现都会误伤，所以这里连多选都不做。
 */
@Composable
internal fun OrphanTaskList(
    tasks: List<OrphanTaskInfo>,
    canDelete: Boolean,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (canDelete) {
                "发现 ${tasks.size} 个失效的自启任务（指向已不存在的程序）"
            } else {
                "发现 ${tasks.size} 个失效的自启任务；删除需要管理员权限（以管理员运行后这里会出现删除按钮）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tasks.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${task.name} → ${task.command}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(task.name) }, enabled = canDelete) { Text("删除") }
            }
        }
    }
}

/** 模型页的回调打包：设置详情窗格参数已经很多，别再平铺十几个函数进来。 */
data class ModelPageCallbacks(
    val onProvider: (String) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onContextChange: (String) -> Unit = {},
    val onSaveContext: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onConfirmPending: () -> Unit = {},
    val onDismissPending: () -> Unit = {},
    val onReloadCatalog: () -> Unit = {},
    val onAssignAux: (AuxTaskDto) -> Unit = {},
    val onResetAux: () -> Unit = {},
    val onRefreshAux: () -> Unit = {},
    val onAddFallback: () -> Unit = {},
    val onRemoveFallback: (Int) -> Unit = {},
    val onSaveFallback: () -> Unit = {},
    val onRefreshFallback: () -> Unit = {},
)

/** 自定义端点页的回调打包（同 [ModelPageCallbacks]，避免详情窗格参数再加十来个）。 */
data class EndpointPageCallbacks(
    val onCreate: () -> Unit = {},
    val onEdit: (CustomEndpointDto) -> Unit = {},
    val onDraftChange: (EndpointDraft) -> Unit = {},
    val onValidate: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onAskDelete: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

/** MoA 页的回调打包。 */
data class MoaPageCallbacks(
    val onSelectPreset: (String) -> Unit = {},
    val onAddPreset: () -> Unit = {},
    val onDeletePreset: () -> Unit = {},
    val onSetDefault: () -> Unit = {},
    val onSetActive: () -> Unit = {},
    val onPresetChange: (MoaPresetDto) -> Unit = {},
    val onAddReference: () -> Unit = {},
    val onRemoveReference: (Int) -> Unit = {},
    val onReferenceChange: (Int, MoaSlotDto) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

@Composable
internal fun FieldRow(field: SettingsField, value: String, onEdit: (String, String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, style = MaterialTheme.typography.bodyMedium)
            if (field.scope == SettingsScope.LIVE) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "即时",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        field.hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))

        when (field.kind) {
            SettingsKind.BOOLEAN -> Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onEdit(field.key, it.toString()) },
            )

            SettingsKind.ENUM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field.enumValues.forEach { option ->
                    FilterChip(
                        selected = value.equals(option, ignoreCase = true),
                        onClick = { onEdit(field.key, option) },
                        label = { Text(field.enumLabels[option] ?: option) },
                    )
                }
            }

            SettingsKind.NUMBER, SettingsKind.TEXT -> OutlinedTextField(
                value = value,
                onValueChange = { onEdit(field.key, it) },
                singleLine = true,
                modifier = Modifier.widthIn(min = 260.dp, max = 480.dp).fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
