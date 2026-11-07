package dev.shinglem.hermes.feature.editors

import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AWT 文件选择框。
 *
 * `FileDialog.setVisible(true)` 是**模态且阻塞**的，必须放到 IO 线程，
 * 否则 Compose 的帧循环会跟着停（表现为整个窗口卡住，直到用户选完）。
 */
actual suspend fun pickExecutableFile(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dialog = FileDialog(null as Frame?, "选择编辑器可执行文件", FileDialog.LOAD).apply {
            file = "*.exe"
            isVisible = true
        }
        val directory = dialog.directory ?: return@runCatching null
        val file = dialog.file ?: return@runCatching null
        "$directory$file"
    }.getOrNull()
}

actual suspend fun pickJsonFile(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dialog = FileDialog(null as Frame?, "选择会话 JSON 文件", FileDialog.LOAD).apply {
            file = "*.json"
            isVisible = true
        }
        val directory = dialog.directory ?: return@runCatching null
        val file = dialog.file ?: return@runCatching null
        "$directory$file"
    }.getOrNull()
}
