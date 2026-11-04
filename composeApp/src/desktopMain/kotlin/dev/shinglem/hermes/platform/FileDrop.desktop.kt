package dev.shinglem.hermes.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberFileDropTarget(
    onFiles: (paths: List<String>) -> Unit,
    onText: (text: String) -> Unit,
    onHover: (hovering: Boolean) -> Unit,
): DragAndDropTarget {
    // 回调每次重组都是新 lambda：包一层，避免目标对象被重建（重建会丢当前拖放会话）。
    val filesRef = rememberUpdatedState(onFiles)
    val textRef = rememberUpdatedState(onText)
    val hoverRef = rememberUpdatedState(onHover)
    return remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hoverRef.value(false)
                return when (val data = event.dragData()) {
                    is DragData.FilesList -> {
                        val paths = data.readFiles().mapNotNull(::uriToPath)
                        if (paths.isEmpty()) {
                            false
                        } else {
                            filesRef.value(paths)
                            true
                        }
                    }

                    is DragData.Text -> {
                        val text = data.readText()
                        if (text.isBlank()) {
                            false
                        } else {
                            textRef.value(text)
                            true
                        }
                    }

                    else -> false
                }
            }

            override fun onEntered(event: DragAndDropEvent) = hoverRef.value(true)

            override fun onExited(event: DragAndDropEvent) = hoverRef.value(false)

            override fun onEnded(event: DragAndDropEvent) = hoverRef.value(false)
        }
    }
}

/** `readFiles()` 给的是 URI（`file:/C:/x`），转成本地绝对路径；解析失败就丢弃该项。 */
private fun uriToPath(raw: String): String? = runCatching {
    val uri = URI(raw)
    if (uri.scheme.equals("file", ignoreCase = true)) File(uri).absolutePath else null
}.getOrNull()

actual suspend fun pickFiles(title: String, imagesOnly: Boolean): List<String> =
    withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (imagesOnly) {
            dialog.setFilenameFilter { _, name -> isImagePath(name) }
        }
        // setVisible(true) 阻塞到对话框关闭；AWT 模态对话框自带事件循环，
        // 所以放在 IO 线程而非 UI 线程调用。
        dialog.isVisible = true
        dialog.files.orEmpty().map { it.absolutePath }
    }

actual suspend fun pickFile(title: String): String? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isMultipleMode = false
    dialog.isVisible = true
    dialog.files?.firstOrNull()?.absolutePath
}

actual suspend fun pickSaveFile(title: String, defaultName: String): String? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    // SAVE 模式下完整路径是 `directory` + `file`（`files` 往往为空）。
    val name = dialog.file ?: return@withContext null
    File(dialog.directory.orEmpty(), name).absolutePath
}