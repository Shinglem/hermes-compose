package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.error.toNotice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.MessagingPlatformDto
import dev.shinglem.hermes.core.contract.PlatformEnvVarDto
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.capabilities_load_failed
import dev.shinglem.hermes.resources.capabilities_platforms
import dev.shinglem.hermes.resources.capabilities_retry
import dev.shinglem.hermes.resources.capability_platform_collapse
import dev.shinglem.hermes.resources.capability_platform_configure
import dev.shinglem.hermes.resources.capability_platform_empty
import dev.shinglem.hermes.resources.capability_platform_empty_body
import dev.shinglem.hermes.resources.capability_platform_env_hint
import dev.shinglem.hermes.resources.capability_platform_home
import dev.shinglem.hermes.resources.capability_platform_is_set
import dev.shinglem.hermes.resources.capability_platform_required
import dev.shinglem.hermes.resources.capability_platform_save
import dev.shinglem.hermes.resources.capability_platform_saving
import dev.shinglem.hermes.resources.capability_platform_state_connected
import dev.shinglem.hermes.resources.capability_platform_state_disabled
import dev.shinglem.hermes.resources.capability_platform_state_error
import dev.shinglem.hermes.resources.capability_platform_subtitle
import dev.shinglem.hermes.resources.capability_platform_test
import dev.shinglem.hermes.resources.capability_platform_testing
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.icon.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 消息平台页：卡片列表 + 状态 chip + 展开配置表单（`env_vars`）+ 保存 / 测试连接。
 *
 * 表单只提交用户填过的键（后端 `env` 是增量语义）；已保存的密钥不回显，
 * 因此输入框用占位提示"已设置"而不是填星号 —— 免得用户以为那是真值。
 */
@Composable
fun PlatformsPane(
    state: CapabilityState,
    onToggleDetail: (String) -> Unit,
    onFieldChange: (platformId: String, key: String, value: String) -> Unit,
    onSave: (platformId: String, enabled: Boolean?) -> Unit,
    onTest: (platformId: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadable = state.platforms
    val platforms = (loadable as? Loadable.Ready)?.value.orEmpty()
    val connected = platforms.count { it.state == "connected" }

    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(
            title = stringResource(Res.string.capabilities_platforms),
            subtitle = if (loadable is Loadable.Ready) {
                stringResource(Res.string.capability_platform_subtitle, platforms.size, connected)
            } else {
                ""
            },
            onRefresh = onRefresh,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    PlatformList(stale, state, onToggleDetail, onFieldChange, onSave, onTest)
                }
            }

            is Loadable.Ready -> if (loadable.value.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    EmptyState(
                        title = stringResource(Res.string.capability_platform_empty),
                        body = stringResource(Res.string.capability_platform_empty_body),
                    )
                }
            } else {
                PlatformList(loadable.value, state, onToggleDetail, onFieldChange, onSave, onTest)
            }
        }
    }
}

@Composable
private fun PlatformList(
    platforms: List<MessagingPlatformDto>,
    state: CapabilityState,
    onToggleDetail: (String) -> Unit,
    onFieldChange: (String, String, String) -> Unit,
    onSave: (String, Boolean?) -> Unit,
    onTest: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(platforms, key = { it.id }) { platform ->
            PlatformRow(
                platform = platform,
                expanded = state.expandedPlatform == platform.id,
                busyKey = state.busyKey,
                draft = state.platformDrafts[platform.id].orEmpty(),
                onToggleDetail = { onToggleDetail(platform.id) },
                onFieldChange = { key, value -> onFieldChange(platform.id, key, value) },
                onSave = { enabled -> onSave(platform.id, enabled) },
                onTest = { onTest(platform.id) },
            )
        }
    }
}

@Composable
private fun PlatformRow(
    platform: MessagingPlatformDto,
    expanded: Boolean,
    busyKey: String?,
    draft: Map<String, String>,
    onToggleDetail: () -> Unit,
    onFieldChange: (String, String) -> Unit,
    onSave: (Boolean?) -> Unit,
    onTest: () -> Unit,
) {
    val saving = busyKey == platformBusyKey(platform.id, PlatformAction.SAVE)
    val testing = busyKey == platformBusyKey(platform.id, PlatformAction.TEST)
    val busy = saving || testing
    // 开关改动是否随保存一起提交：默认只保存已填的密钥，开关单独点才提交。
    var pendingEnabled by remember(platform.id) { mutableStateOf<Boolean?>(null) }
    val enabledNow = pendingEnabled ?: platform.enabled

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
                    Text(text = platform.name, style = MaterialTheme.typography.bodyMedium)
                    StateChip(platform.state)
                    if (platform.homeChannel?.isNotBlank() == true) {
                        Tag(text = stringResource(Res.string.capability_platform_home, platform.homeChannel))
                    }
                }
                if (platform.description.isNotBlank()) {
                    Text(
                        text = platform.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (platform.state == "error" && platform.errorMessage?.isNotBlank() == true) {
                    Text(
                        text = platform.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Switch(
                checked = enabledNow,
                onCheckedChange = { pendingEnabled = it },
                enabled = !busy,
            )
            TextButton(onClick = onToggleDetail) {
                Text(
                    stringResource(
                        if (expanded) Res.string.capability_platform_collapse else Res.string.capability_platform_configure,
                    ),
                )
            }
        }

        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.capability_platform_env_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    platform.envVars.forEach { envVar ->
                        EnvVarField(
                            envVar = envVar,
                            value = draft[envVar.key].orEmpty(),
                            onValueChange = { onFieldChange(envVar.key, it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                onSave(pendingEnabled)
                                pendingEnabled = null
                            },
                            enabled = !busy,
                        ) {
                            Text(
                                stringResource(
                                    if (saving) Res.string.capability_platform_saving else Res.string.capability_platform_save,
                                ),
                            )
                        }
                        TextButton(onClick = onTest, enabled = !busy) {
                            Text(
                                stringResource(
                                    if (testing) Res.string.capability_platform_testing else Res.string.capability_platform_test,
                                ),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EnvVarField(envVar: PlatformEnvVarDto, value: String, onValueChange: (String) -> Unit) {
    val requiredTag = stringResource(Res.string.capability_platform_required)
    val label = buildString {
        append(envVar.prompt.ifBlank { envVar.key })
        if (envVar.required) append(" ").append(requiredTag)
    }
    val setLabel = stringResource(Res.string.capability_platform_is_set)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            if (envVar.isSet) {
                val redacted = envVar.redactedValue?.takeIf { it.isNotBlank() }
                Text(
                    text = redacted?.let { "$setLabel（$it）" } ?: setLabel,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        supportingText = {
            val hint = listOfNotNull(
                envVar.key,
                envVar.description.takeIf { it.isNotBlank() },
                envVar.help.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        singleLine = true,
        visualTransformation = if (envVar.isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/** 平台状态 chip：`connected` 绿、`error` 红、其余中立。 */
@Composable
private fun StateChip(state: String) {
    val (label, color) = when (state) {
        "connected" -> stringResource(Res.string.capability_platform_state_connected) to
            MaterialTheme.colorScheme.primary
        "error" -> stringResource(Res.string.capability_platform_state_error) to
            MaterialTheme.colorScheme.error
        "disabled" -> stringResource(Res.string.capability_platform_state_disabled) to
            MaterialTheme.colorScheme.onSurfaceVariant
        else -> (state.ifBlank { "—" }) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
