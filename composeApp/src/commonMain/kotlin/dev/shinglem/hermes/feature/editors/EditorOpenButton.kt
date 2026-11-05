package dev.shinglem.hermes.feature.editors

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 工具卡片右上角的「打开」按钮。
 *
 * 只有一个可执行项时点一下直接打开（多一次点击没有信息量）；有多个时弹菜单，
 * 让用户挑「哪个编辑器 × 哪种打开方式」。
 *
 * 路径算不出来（非文件类工具、相对路径又没有会话 cwd）时**整个按钮不出现**，
 * 不做置灰占位。
 */
@Composable
fun EditorOpenButton(
    toolName: String,
    argumentsJson: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalEditorLaunchContext.current ?: return
    if (context.editors.profiles.none { it.usable }) return

    val target = remember(toolName, argumentsJson, context.cwd, context.userHome) {
        toolFileRefs(toolName, argumentsJson, context.cwd, context.userHome).firstOrNull()
    } ?: return
    val dir = remember(target.path) { parentDirOf(target.path) }
    val items = remember(context.editors, target.path, dir, context.cwd) {
        editorMenuItems(context.editors, target.path, dir, context.cwd, target.line)
    }
    if (items.isEmpty()) return

    var menuOpen by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun run(request: EditorOpenRequest) {
        failed = !launchEditorProcess(request.profile.exePath, request.args)
    }

    Box(modifier) {
        TextButton(
            onClick = { if (items.size == 1) run(items.first()) else menuOpen = true },
        ) {
            Text(
                text = if (failed) "打开失败" else "打开",
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            items.forEach { request ->
                DropdownMenuItem(
                    text = { Text("${request.profile.name} · ${request.kind.label}") },
                    onClick = {
                        menuOpen = false
                        run(request)
                    },
                )
            }
        }
    }
}
