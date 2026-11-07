package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 设置 → 提供方 →「凭据池」。
 *
 * 与「密钥」（`/api/env`，一个 provider 一个值）不是一回事：池是**同一个 provider 的多把 key**，
 * 后端按优先级轮换、失败自动切下一把。这里只做增删与查看状态，明文不回显（后端只给脱敏预览）。
 */
@Composable
fun CredentialPoolPage(
    state: CredentialPoolUiState,
    profileKey: String?,
    profileScope: ProfileScope? = null,
    onRefresh: () -> Unit,
    onProviderChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onAdd: () -> Unit,
    onAskDelete: (String, Int) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(profileKey) {
        if (state.data == null) onRefresh()
    }

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        MaintenanceHeader(
            title = "凭据池",
            subtitle = "同一个 provider 可以放多把 key，后端按顺序轮换并在失败时自动换下一把。" +
                "这里只显示脱敏预览；新增的 key 直接交给后端保存。",
        )
        ProfileScopeChips(profileScope)
        Spacer(Modifier.height(14.dp))

        val data = state.data
        if (data == null) {
            Text(
                if (state.loading) "正在读取…" else "尚未读取到凭据池",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (data.providers.isEmpty()) {
            Text(
                "池里还没有凭据。下面新增的 key 会作为该 provider 的一条独立凭据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            data.providers.forEach { provider ->
                Text(provider.provider, style = MaterialTheme.typography.labelMedium)
                provider.entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "#${entry.index} ${entry.label.ifBlank { entry.authType }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MonoLine(
                                buildString {
                                    append(entry.tokenPreview.ifBlank { "(无预览)" })
                                    append(" · ${entry.source}")
                                    append(" · 优先级 ${entry.priority}")
                                    append(" · 调用 ${entry.requestCount}")
                                    if (entry.hasRefresh) append(" · 可刷新")
                                    entry.lastStatus?.let { append(" · $it") }
                                },
                            )
                        }
                        TextButton(onClick = { onAskDelete(provider.provider, entry.index) }) { Text("删除") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        Text("新增一条凭据", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.provider,
            onValueChange = onProviderChange,
            label = { Text("provider（如 anthropic / openai）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onKeyChange,
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.label,
            onValueChange = onLabelChange,
            label = { Text("备注（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAdd,
                enabled = !state.saving && state.provider.isNotBlank() && state.apiKey.isNotBlank(),
            ) { Text(if (state.saving) "正在保存…" else "加入凭据池") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }

        MaintenanceStatus(state.notice, state.error, onDismissStatus)
    }

    state.pendingDelete?.let { (provider, index) ->
        MaintenanceConfirm(
            title = "删除凭据",
            text = "将删除 $provider 的第 $index 条凭据，删除后轮换时不再使用它。确定继续？",
            confirmLabel = "删除",
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete,
        )
    }
}
