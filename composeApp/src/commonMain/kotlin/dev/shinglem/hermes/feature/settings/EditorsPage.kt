package dev.shinglem.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.config.EditorAction
import dev.shinglem.hermes.core.config.EditorActionKind
import dev.shinglem.hermes.core.config.EditorProfile
import dev.shinglem.hermes.core.config.EditorsOptions
import dev.shinglem.hermes.feature.editors.pickExecutableFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「桌面 → 外部编辑器」。
 *
 * 配置是列表（可多个）：AI 改完文件后，工具卡片旁的「打开」按钮按默认配置直开，
 * 有多个且没设默认时弹菜单让用户挑。
 *
 * 每个配置有五种动作各一套「开关 + 参数模板」——不同编辑器命令行差异很大，
 * 写死没法兼顾。关掉某个动作时会回退成「打开文件」（见 `planEditorOpen`）。
 */
@Composable
fun EditorsPage(
    editors: EditorsOptions,
    onSave: (EditorsOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<EditorProfile?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("外部编辑器", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "AI 修改或新建文件后，工具卡片旁会出现「打开」按钮，用这里的配置在外部编辑器里看文件、" +
                "所在文件夹或项目。不内置编辑器 —— 交给 VSCode / IDEA 更省事。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (editors.profiles.isEmpty()) {
            Text(
                text = "还没有配置。添加一个（例如 VS Code）后，工具卡片上才会出现「打开」按钮。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        editors.profiles.forEach { profile ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name.ifBlank { profile.id } +
                            if (editors.defaultId == profile.id) "（默认）" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = profile.exePath.ifBlank { "未设置可执行文件" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "已启用：" + EditorActionKind.entries
                            .filter { enabledOf(profile, it) }
                            .joinToString("、") { it.label }
                            .ifBlank { "无" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editing = profile }) { Text("编辑") }
                TextButton(
                    onClick = { onSave(editors.copy(defaultId = profile.id)) },
                    enabled = editors.defaultId != profile.id,
                ) { Text("设为默认") }
                TextButton(
                    onClick = {
                        onSave(
                            editors.copy(
                                profiles = editors.profiles.filterNot { it.id == profile.id },
                                defaultId = editors.defaultId?.takeIf { it != profile.id },
                            ),
                        )
                    },
                ) { Text("删除") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        OutlinedButton(
            onClick = {
                editing = EditorProfile(
                    id = "editor-${System.currentTimeMillis()}",
                    name = "VS Code",
                    exePath = "",
                )
            },
        ) { Text("添加编辑器") }

        Text(
            text = "提示：填 VSCode 安装目录下的 Code.exe（不是 bin\\code.cmd —— 批处理也能用，但会多起一个控制台进程）。" +
                "参数模板里的占位符：{path} 文件、{dir} 目录、{line} 行号、{file1}/{file2} 对比用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    editing?.let { draft ->
        EditorEditDialog(
            draft = draft,
            isNew = editors.profiles.none { it.id == draft.id },
            scope = scope,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                val exists = editors.profiles.any { it.id == updated.id }
                val profiles = if (exists) {
                    editors.profiles.map { if (it.id == updated.id) updated else it }
                } else {
                    editors.profiles + updated
                }
                onSave(editors.copy(profiles = profiles))
                editing = null
            },
        )
    }
}

@Composable
private fun EditorEditDialog(
    draft: EditorProfile,
    isNew: Boolean,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onConfirm: (EditorProfile) -> Unit,
) {
    var profile by remember(draft.id) { mutableStateOf(draft) }
    val canSave = profile.name.isNotBlank() && profile.exePath.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加编辑器" else "编辑编辑器") },
        text = {
            Column(
                modifier = Modifier.width(520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { profile = profile.copy(name = it) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = profile.exePath,
                        onValueChange = { profile = profile.copy(exePath = it) },
                        label = { Text("可执行文件") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pickExecutableFile()?.let { picked -> profile = profile.copy(exePath = picked) }
                            }
                        },
                    ) { Text("浏览…") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EditorActionKind.entries.forEach { kind ->
                    EditorActionRow(
                        kind = kind,
                        action = actionOf(profile, kind),
                        onChange = { updated -> profile = withAction(profile, kind, updated) },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(profile) }, enabled = canSave) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditorActionRow(
    kind: EditorActionKind,
    action: EditorAction,
    onChange: (EditorAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "占位符：" + kind.placeholders.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = action.enabled,
                onCheckedChange = { onChange(action.copy(enabled = it)) },
            )
        }
        if (action.enabled) {
            OutlinedTextField(
                value = action.args,
                onValueChange = { onChange(action.copy(args = it)) },
                placeholder = { Text(kind.defaultTemplate) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (action.args.isNotBlank()) {
                        TextButton(onClick = { onChange(action.copy(args = "")) }) { Text("默认") }
                    }
                },
            )
        }
    }
}

private fun actionOf(profile: EditorProfile, kind: EditorActionKind): EditorAction = when (kind) {
    EditorActionKind.OPEN_FILE -> profile.openFile
    EditorActionKind.OPEN_FILE_AT_LINE -> profile.openFileAtLine
    EditorActionKind.OPEN_FOLDER -> profile.openFolder
    EditorActionKind.OPEN_PROJECT -> profile.openProject
    EditorActionKind.OPEN_DIFF -> profile.openDiff
}

private fun withAction(profile: EditorProfile, kind: EditorActionKind, action: EditorAction): EditorProfile =
    when (kind) {
        EditorActionKind.OPEN_FILE -> profile.copy(openFile = action)
        EditorActionKind.OPEN_FILE_AT_LINE -> profile.copy(openFileAtLine = action)
        EditorActionKind.OPEN_FOLDER -> profile.copy(openFolder = action)
        EditorActionKind.OPEN_PROJECT -> profile.copy(openProject = action)
        EditorActionKind.OPEN_DIFF -> profile.copy(openDiff = action)
    }

private fun enabledOf(profile: EditorProfile, kind: EditorActionKind): Boolean = actionOf(profile, kind).enabled
