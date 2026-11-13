package dev.shinglem.hermes.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.component.settings.SecurityComponent
import dev.shinglem.hermes.component.settings.SecurityIntent
import dev.shinglem.hermes.component.settings.SecurityState
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.settings.ConfigFieldKind
import dev.shinglem.hermes.feature.settings.ConfigFieldSpec
import dev.shinglem.hermes.feature.settings.SecurityTab
import dev.shinglem.hermes.feature.settings.securityFieldsOf

/**
 * 安全三页（审批 / 隐私与网络 / 检查点）。
 *
 * S2 起 UI 只做两件事：**读 `component.state`、发 `onIntent`**。
 * 迁移前它收的是 `state + onEdit + onSave + onReload` 四个参数（`SettingsPane` 里
 * 再拼一层回调），现在只剩一个 Component —— 页面不知道该请求什么、什么时候请求，
 * 那是 Component 的事。
 *
 * 三页共用同一个表单渲染器，只是 `specs` 不同：
 * 结构与迁移前完全一致（只有字段清单不一样，所以是一个组件 + 三份 spec）。
 */
@Composable
fun SecurityContent(
    component: SecurityComponent,
    tab: SecurityTab,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    SecurityForm(
        tab = tab,
        specs = securityFieldsOf(tab),
        state = state,
        onEdit = { path, text -> component.onIntent(SecurityIntent.Edit(path, text)) },
        onSave = { specs -> component.onIntent(SecurityIntent.Save(specs)) },
        onReload = { component.onIntent(SecurityIntent.Reload) },
        modifier = modifier,
    )
}

@Composable
private fun SecurityForm(
    tab: SecurityTab,
    specs: List<ConfigFieldSpec>,
    state: SecurityState,
    onEdit: (String, String) -> Unit,
    onSave: (List<ConfigFieldSpec>) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(titleOf(tab), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitleOf(tab),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        when (val config = state.config) {
            is Loadable.Idle, is Loadable.Loading -> LoadingRow()

            is Loadable.Failed -> Column {
                Text(
                    config.error.toNotice(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onReload) { Text("重试") }
            }

            is Loadable.Ready -> {
                specs.forEach { spec ->
                    FieldBlock(
                        spec = spec,
                        value = state.valueOf(spec),
                        enabled = !state.saving,
                        onChange = { onEdit(spec.path, it) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onSave(specs) }, enabled = !state.saving && state.dirty) {
                        Text(if (state.saving) "正在保存…" else "保存")
                    }
                    TextButton(onClick = onReload, enabled = !state.saving) { Text("重新读取") }
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

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.height(16.dp).widthIn(max = 16.dp),
            strokeWidth = 2.dp,
        )
        Text("正在读取后端配置…", style = MaterialTheme.typography.bodySmall)
    }
}

private fun titleOf(tab: SecurityTab): String = when (tab) {
    SecurityTab.APPROVALS -> "审批"
    SecurityTab.PRIVACY -> "隐私与网络"
    SecurityTab.CHECKPOINTS -> "检查点"
}

private fun subtitleOf(tab: SecurityTab): String = when (tab) {
    SecurityTab.APPROVALS ->
        "危险动作需要确认的策略；改完立即对后端生效（审批模式会热广播给运行中的会话）。"
    SecurityTab.PRIVACY -> "日志脱敏与内网访问限制；改完对后续请求生效。"
    SecurityTab.CHECKPOINTS -> "文件改动快照；开关对**新会话**生效。"
}

@Composable
private fun FieldBlock(
    spec: ConfigFieldSpec,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(spec.label, style = MaterialTheme.typography.bodyMedium)
        spec.hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        when (spec.kind) {
            ConfigFieldKind.BOOLEAN -> Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onChange(it.toString()) },
                enabled = enabled,
            )

            ConfigFieldKind.ENUM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.enumValues.forEach { option ->
                    FilterChip(
                        selected = value.equals(option, ignoreCase = true),
                        onClick = { onChange(option) },
                        label = { Text(option) },
                        enabled = enabled,
                    )
                }
            }

            ConfigFieldKind.NUMBER, ConfigFieldKind.TEXT, ConfigFieldKind.LIST -> OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(min = 260.dp, max = 520.dp).fillMaxWidth(),
            )
        }
    }
}
