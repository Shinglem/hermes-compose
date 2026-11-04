package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.contract.ProfileAdminDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.platform.pickFile
import dev.shinglem.hermes.platform.pickSaveFile
import dev.shinglem.hermes.ui.component.NavRow
import kotlinx.coroutines.launch

/**
 * 配置档案页（master-detail）：左侧档案列表 + 右侧详情与 SOUL.md 编辑。
 *
 * 文件选择器（导入 / 导出）放在页面里用协程调，ViewModel 只收最终路径 —— 这样逻辑层
 * 可以单测，也不用把 AWT 的阻塞对话框塞进业务代码。
 */
@Composable
fun ProfilesPage(
    state: ProfilesState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onSoulChange: (String) -> Unit,
    onSaveSoul: () -> Unit,
    onStartCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onDraftName: (String) -> Unit,
    onDraftClone: (String?) -> Unit,
    onDraftSoul: (String) -> Unit,
    onSubmitCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onImport: (String) -> Unit,
    onExport: (String, String) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<ProfileAdminDto?>(null) }

    Row(modifier = modifier.fillMaxSize()) {
        ProfileListPane(
            state = state,
            onSelect = onSelect,
            onRefresh = onRefresh,
            onCreate = onStartCreate,
            onImport = {
                scope.launch {
                    pickFile("选择档案归档（.tar.gz / .tgz）")?.let(onImport)
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.fillMaxHeight().width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ProfileDetailPane(
                state = state,
                onSoulChange = onSoulChange,
                onSaveSoul = onSaveSoul,
                onRename = onRename,
                onExport = { name ->
                    scope.launch {
                        pickSaveFile("导出档案", "$name.tar.gz")?.let { path -> onExport(name, path) }
                    }
                },
                onRequestDelete = { confirmDelete = it },
                modifier = Modifier.weight(1f),
            )
            state.notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }

    state.draft?.let { draft ->
        CreateProfileDialog(
            draft = draft,
            candidates = state.profiles,
            busy = state.busyKey == "create",
            onName = onDraftName,
            onClone = onDraftClone,
            onSoul = onDraftSoul,
            onCancel = onCancelCreate,
            onSubmit = onSubmitCreate,
        )
    }

    confirmDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除档案 ${profile.name}？") },
            text = { Text("会删除该档案的目录、技能与凭据，不可撤销。${if (profile.isDefault) "（默认档案不可删除）" else ""}") },
            confirmButton = {
                TextButton(
                    enabled = !profile.isDefault,
                    onClick = {
                        confirmDelete = null
                        onDelete(profile.name)
                        onDismissNotice()
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProfileListPane(
    state: ProfilesState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.width(240.dp).fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("配置档案", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.busyKey != null) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = onCreate, enabled = state.busyKey == null) { Text("新建") }
            OutlinedButton(onClick = onImport, enabled = state.busyKey == null) { Text("导入") }
        }
        Spacer(Modifier.height(6.dp))
        when (val list = state.list) {
            is Loadable.Idle, is Loadable.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }

            is Loadable.Failed -> Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = list.error.toNotice(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRefresh) { Text("重试") }
            }

            is Loadable.Ready -> LazyColumn {
                items(list.value, key = { it.name }) { profile ->
                    NavRow(
                        label = if (profile.isDefault) "${profile.label} · 默认" else profile.label,
                        selected = profile.name == state.selected,
                        onClick = { onSelect(profile.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailPane(
    state: ProfilesState,
    onSoulChange: (String) -> Unit,
    onSaveSoul: () -> Unit,
    onRename: (String, String) -> Unit,
    onExport: (String) -> Unit,
    onRequestDelete: (ProfileAdminDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.current
    if (profile == null) {
        Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("选择左侧的一个档案", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    var renameDraft by remember(profile.name) { mutableStateOf(profile.name) }

    Column(
        modifier = modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.label, style = MaterialTheme.typography.titleMedium)
            if (profile.isDefault) Badge("默认")
            if (profile.gatewayRunning) Badge("网关运行中")
            if (profile.hasEnv) Badge("独立凭据")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = profile.path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("模型", profile.modelLabel.ifBlank { "（未设置）" })
        KeyValueRow("技能数", profile.skillCount.toString())
        if (profile.description.isNotBlank()) {
            KeyValueRow("说明", profile.description)
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── 重命名 ─────────────────────────────────────────────────────
        Spacer(Modifier.height(14.dp))
        Text("重命名", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = renameDraft,
                onValueChange = { renameDraft = slugifyProfileName(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onRename(profile.name, renameDraft) },
                enabled = state.busyKey == null &&
                    isProfileNameValid(renameDraft) &&
                    renameDraft != profile.name,
            ) { Text("应用") }
        }

        // ── SOUL.md ───────────────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SOUL.md", style = MaterialTheme.typography.labelLarge)
            when (val soul = state.soul) {
                is Loadable.Ready -> if (!soul.value.exists) Badge("尚未创建")
                is Loadable.Failed -> Badge("读取失败")
                else -> Unit
            }
            if (state.soulDirty) Badge("未保存")
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onSaveSoul,
                enabled = state.busyKey == null && state.soulDirty,
            ) { Text("保存") }
        }
        when (val soul = state.soul) {
            is Loadable.Idle, is Loadable.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }

            is Loadable.Failed -> Text(
                text = soul.error.toNotice(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            is Loadable.Ready -> OutlinedTextField(
                value = state.soulDraft,
                onValueChange = onSoulChange,
                placeholder = { Text("这个档案的人格 / 系统提示词（Markdown）") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp)
                    .padding(top = 8.dp),
            )
        }

        // ── 导出 / 删除 ───────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport(profile.name) }, enabled = state.busyKey == null) {
                Text("导出 .tar.gz")
            }
            OutlinedButton(
                onClick = { onRequestDelete(profile) },
                enabled = state.busyKey == null && !profile.isDefault,
            ) { Text("删除档案") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CreateProfileDialog(
    draft: ProfileCreateDraft,
    candidates: List<ProfileAdminDto>,
    busy: Boolean,
    onName: (String) -> Unit,
    onClone: (String?) -> Unit,
    onSoul: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("新建配置档案") },
        text = {
            Column(modifier = Modifier.width(520.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onName,
                    singleLine = true,
                    label = { Text("档案名") },
                    supportingText = {
                        Text(
                            if (draft.name.isBlank()) "小写字母或数字开头，只允许 a-z 0-9 _ -"
                            else if (draft.nameValid) "合法" else "不合法的档案名",
                            color = if (draft.name.isBlank() || draft.nameValid) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("克隆来源", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CloneChip("不克隆", draft.cloneFrom == null) { onClone(null) }
                    candidates.take(6).forEach { candidate ->
                        CloneChip(candidate.name, draft.cloneFrom == candidate.name) { onClone(candidate.name) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.soul,
                    onValueChange = onSoul,
                    label = { Text("SOUL.md（可选）") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = draft.canSubmit && !busy) {
                Text(if (busy) "正在创建…" else "创建")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun CloneChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.padding(end = 4.dp),
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
