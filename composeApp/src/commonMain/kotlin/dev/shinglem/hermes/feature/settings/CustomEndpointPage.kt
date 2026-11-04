package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.CustomEndpointDto
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 提供方 · 自定义端点页。
 *
 * 列表态与编辑态共用一个 composable 分支：`state.draft == null` 看列表，
 * 否则看表单。端点保存在后端 profile 的 `config.yaml`，激活后主模型槽位指向它。
 */
@Composable
fun CustomEndpointPage(
    state: CustomEndpointState,
    onCreate: () -> Unit,
    onEditEndpoint: (CustomEndpointDto) -> Unit,
    onDraftChange: (EndpointDraft) -> Unit,
    onValidate: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onActivate: (String) -> Unit,
    onAskDelete: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    if (draft != null) {
        EndpointForm(
            state = state,
            draft = draft,
            onChange = onDraftChange,
            onValidate = onValidate,
            onSave = onSave,
            onCancel = onCancel,
            modifier = modifier,
        )
    } else {
        EndpointList(
            state = state,
            onCreate = onCreate,
            onEditEndpoint = onEditEndpoint,
            onActivate = onActivate,
            onAskDelete = onAskDelete,
            onRefresh = onRefresh,
            modifier = modifier,
        )
    }

    state.pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除自定义端点？") },
            text = { Text("会从该 profile 的 config.yaml 里移除 $id，并清掉它对应的环境变量。若主模型正指向它，也会被解绑。") },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun EndpointList(
    state: CustomEndpointState,
    onCreate: () -> Unit,
    onEditEndpoint: (CustomEndpointDto) -> Unit,
    onActivate: (String) -> Unit,
    onAskDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val endpoints = state.endpoints
    when (endpoints) {
        is Loadable.Idle, is Loadable.Loading -> Column(modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("正在读取自定义端点…", style = MaterialTheme.typography.bodySmall)
            }
        }

        is Loadable.Failed -> Column(modifier.fillMaxSize()) {
            EmptyState(
                title = "读取自定义端点失败",
                body = endpoints.error.toNotice(),
                icon = AppIcons.Error,
                action = { TextButton(onClick = onRefresh) { Text("重试") } },
            )
        }

        is Loadable.Ready -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        ) {
            item(key = "header") {
                Column {
                    Text("自定义端点", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "接入任意 OpenAI 兼容服务（本地 vLLM / Ollama / 自建网关）。保存写入后端 config.yaml，激活即把主模型指过去。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCreate, enabled = state.busyKey == null) { Text("添加端点") }
                        OutlinedButton(onClick = onRefresh, enabled = state.busyKey == null) { Text("重新读取") }
                    }
                    if (state.current.baseUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "主模型当前指向 ${state.current.provider.ifBlank { "自定义" }} / ${state.current.model}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (endpoints.value.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有自定义端点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            items(endpoints.value, key = { it.id }) { endpoint ->
                EndpointRow(
                    endpoint = endpoint,
                    busy = state.busyKey != null,
                    onEdit = { onEditEndpoint(endpoint) },
                    onActivate = { onActivate(endpoint.id) },
                    onDelete = { onAskDelete(endpoint.id) },
                )
            }
            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EndpointRow(
    endpoint: CustomEndpointDto,
    busy: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(endpoint.name.ifBlank { endpoint.id }, style = MaterialTheme.typography.bodyMedium)
            if (endpoint.isCurrent) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (endpoint.hasApiKey) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "已配密钥",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            endpoint.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "模型 ${endpoint.model.ifBlank { "—" }} · ${apiModeLabel(endpoint.apiMode)}" +
                (endpoint.contextLength?.let { " · 上下文 $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit, enabled = !busy) { Text("编辑") }
            TextButton(onClick = onActivate, enabled = !busy && !endpoint.isCurrent) { Text("设为主模型") }
            TextButton(onClick = onDelete, enabled = !busy) { Text("删除") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EndpointForm(
    state: CustomEndpointState,
    draft: EndpointDraft,
    onChange: (EndpointDraft) -> Unit,
    onValidate: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.busyKey != null || state.validating
    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            if (draft.isNew) "添加自定义端点" else "编辑 ${draft.name}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Base URL 填到 API 根（如 http://127.0.0.1:8000/v1）。保存前可先探测，探测只给建议、不拦保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        FormField("名称", draft.name, "例如 本地 Qwen", busy) { onChange(draft.copy(name = it)) }
        FormField("Base URL", draft.baseUrl, "http://127.0.0.1:8000/v1", busy) { onChange(draft.copy(baseUrl = it)) }
        FormField("模型", draft.model, "例如 qwen3-32b", busy) { onChange(draft.copy(model = it)) }
        FormField(
            label = "API Key",
            value = draft.apiKey,
            placeholder = if (draft.isNew) "可留空（端点不校验时）" else "留空 = 保留已保存的密钥",
            busy = busy,
            password = true,
        ) { onChange(draft.copy(apiKey = it)) }
        FormField("上下文长度", draft.contextDraft, "留空 = 自动", busy) {
            onChange(draft.copy(contextDraft = it.filter(Char::isDigit)))
        }

        Spacer(Modifier.height(6.dp))
        ModePicker(draft.apiMode, busy) { onChange(draft.copy(apiMode = it)) }

        Spacer(Modifier.height(6.dp))
        CheckRow("自动发现模型列表（探测 /models）", draft.discoverModels, busy) {
            onChange(draft.copy(discoverModels = it))
        }
        CheckRow("保存后把主模型切到它", draft.makeDefault, busy) {
            onChange(draft.copy(makeDefault = it))
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onValidate, enabled = !busy && draft.canSubmit) {
                Text(if (state.validating) "正在探测…" else "探测")
            }
            Button(onClick = onSave, enabled = !busy && draft.canSubmit) {
                Text(if (state.busyKey == "save") "正在保存…" else "保存")
            }
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
        }

        state.probe?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.probeOk == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        state.error?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    placeholder: String,
    busy: Boolean,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = !busy,
        singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun ModePicker(selected: String, busy: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "传输模式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Box {
            Surface(
                onClick = { open = true },
                enabled = !busy,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(apiModeLabel(selected), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(modifier = Modifier.widthIn(min = 200.dp).heightIn(max = 320.dp)) {
                    API_MODES.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                open = false
                                onSelect(id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = !busy)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
