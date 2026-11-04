package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.error.toNotice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.EnvVarDto
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 提供方密钥页：按 provider 分组列出凭据键，逐个填值保存。
 *
 * 明文只在输入框里存在；已保存的值后端只回 redacted 预览，所以填过一次之后
 * 输入框是空的（占位符显示"已配置"，想换就整个重填）。
 */
@Composable
fun ProviderKeysPage(
    state: ProviderKeysState,
    onEdit: (String, String) -> Unit,
    onSave: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissSaved: () -> Unit,
    onQueryChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 保存提示是一次性的。
    LaunchedEffect(state.savedKey) {
        if (state.savedKey != null) onDismissSaved()
    }

    when (val env = state.env) {
        is Loadable.Idle, is Loadable.Loading -> LoadingCenter()

        is Loadable.Failed -> Column(modifier = modifier.fillMaxSize()) {
            EmptyState(
                title = "提供方配置加载失败",
                body = env.error.toNotice(),
                icon = AppIcons.Error,
                action = { TextButton(onClick = onRefresh) { Text("重试") } },
            )
        }

        is Loadable.Ready -> {
            val groups = state.groups()
            if (groups.isEmpty()) {
                Column(modifier = modifier.fillMaxSize()) {
                    EmptyState(
                        title = "没有可配置的提供方",
                        body = "后端没有返回任何 provider 凭据键。检查 payload 里的 provider 目录是否存在。",
                    )
                }
                return
            }
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            ) {
                item(key = "header") {
                    Column {
                        Text("提供方密钥", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已配置 ${state.configuredCount()} 项。保存后写入后端的 .env，立即生效（新会话开始使用）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            placeholder = { Text("搜索键名、说明或提供方", style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        TextButton(onClick = onRefresh) { Text("重新读取") }
                    }
                }
                state.error?.let { message ->
                    item(key = "error") {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "group-${group.id}") {
                        Text(
                            text = group.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(group.entries) { (key, info) ->
                        ProviderKeyRow(
                            envKey = key,
                            info = info,
                            draft = state.drafts[key].orEmpty(),
                            busy = state.busyKey == key,
                            saved = state.savedKey == key,
                            onEdit = { onEdit(key, it) },
                            onSave = { onSave(key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderKeyRow(
    envKey: String,
    info: EnvVarDto,
    draft: String,
    busy: Boolean,
    saved: Boolean,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = envKey,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            )
            StatusTag(text = if (info.hasValue()) "已配置" else "未配置", configured = info.hasValue())
            if (info.providerPrimary) StatusTag(text = "主键", configured = false)
        }
        if (info.description.isNotBlank()) {
            Text(
                text = info.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        info.redactedValue?.takeIf { it.isNotBlank() && info.hasValue() }?.let { preview ->
            Text(
                text = "当前值：$preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onEdit,
                singleLine = true,
                placeholder = {
                    Text(
                        text = if (info.hasValue()) "输入新值以替换" else "粘贴密钥",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                visualTransformation = if (info.isPassword) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Button(onClick = onSave, enabled = !busy && draft.isNotBlank()) {
                Text(if (saved) "已保存" else "保存")
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StatusTag(text: String, configured: Boolean) {
    val container = if (configured) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (configured) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LoadingCenter() {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("正在读取提供方配置…", style = MaterialTheme.typography.bodySmall)
    }
}
