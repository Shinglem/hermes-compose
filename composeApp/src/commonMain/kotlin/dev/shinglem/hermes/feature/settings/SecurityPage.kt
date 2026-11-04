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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable

/**
 * 安全三页共用的表单页：按 [ConfigFieldSpec] 渲染控件，保存时只提交改动过的字段。
 *
 * 三个页面（审批 / 隐私与网络 / 检查点）结构完全一致，只有字段清单不同 —— 所以是一个组件
 * 加三份 spec，而不是三个几乎一样的页面。
 */
@Composable
fun SecurityFormPage(
    title: String,
    subtitle: String,
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
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        when (val config = state.config) {
            is Loadable.Idle, is Loadable.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).widthIn(max = 16.dp), strokeWidth = 2.dp)
                Text("正在读取后端配置…", style = MaterialTheme.typography.bodySmall)
            }

            is Loadable.Failed -> Column {
                Text(config.error.toNotice(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onReload) { Text("重试") }
            }

            is Loadable.Ready -> {
                specs.forEach { spec ->
                    FieldBlock(
                        spec = spec,
                        value = state.valueOf(spec),
                        enabled = state.busyKey == null,
                        onChange = { onEdit(spec.path, it) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(specs) }, enabled = state.busyKey == null && state.dirty) {
                        Text(if (state.busyKey == "config") "正在保存…" else "保存")
                    }
                    TextButton(onClick = onReload, enabled = state.busyKey == null) { Text("重新读取") }
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
private fun FieldBlock(
    spec: ConfigFieldSpec,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(spec.label, style = MaterialTheme.typography.bodyMedium)
        spec.hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
