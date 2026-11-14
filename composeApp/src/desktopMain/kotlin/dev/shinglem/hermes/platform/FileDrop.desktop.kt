package dev.shinglem.hermes.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.io.File
import java.net.URI

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

// ---------------------------------------------------------------------------
// 系统文件对话框：FileKit（Windows 上走 JNA + COM `IFileOpenDialog`）
//
// 换掉 `java.awt.FileDialog` 的理由（ADR-072 之后又一处"平台隐含契约"）：
// AWT 的模态对话框**自己在 UI 线程上跑事件循环**，而 Tao 进程的 UI 线程是 `main`，
// 不是 AWT 的 EDT —— 在 `Dispatchers.Main` 上调 `dialog.isVisible = true` 会把
// Compose 的组合线程阻塞住（旧实现靠 `withContext(Dispatchers.IO)` 绕开，但那意味着
// 对话框挂在不属于任何窗口模型的后台线程上，弹窗归属/置顶都不可控）。
//
// FileKit 的 Windows 实现是 `WindowsFilePicker` + JNA/COM（实测见 `fileKitProbe`：
// `platform.Current` 在 Windows 上解析成 `WindowsFilePicker`，`AwtFilePicker` /
// `SwingFilePicker` 只在 Linux 分支里当备选）。它是挂起函数，不占用调用线程。
// ---------------------------------------------------------------------------

actual suspend fun pickFiles(title: String, imagesOnly: Boolean): List<String> =
    FileKit.openFilePicker(
        type = if (imagesOnly) FileKitType.Image else FileKitType.File(IMAGE_EXTENSIONS),
        mode = FileKitMode.Multiple(),
        dialogSettings = FileKitDialogSettings(title = title),
    ).orEmpty().map { it.file.absolutePath }

actual suspend fun pickFile(title: String): String? =
    FileKit.openFilePicker(
        type = FileKitType.File(),
        dialogSettings = FileKitDialogSettings(title = title),
    )?.file?.absolutePath

actual suspend fun pickSaveFile(title: String, defaultName: String): String? {
    // 0.17.0 的 `extension` 参数已废弃（它只是"默认扩展名提示"，不是过滤器），
    // 所以把完整文件名作为 `suggestedName` 传进去，扩展名由对话框自己截。
    val suggested = defaultName.substringBeforeLast('.', defaultName)
    val extension = defaultName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
    return FileKit.openFileSaver(
        suggestedName = suggested,
        dialogSettings = FileKitDialogSettings(title = title),
    )?.file?.absolutePath
        // 用户可能在对话框里删掉了扩展名：补回去，否则导出的 tar.gz 没有后缀。
        ?.let { if (extension != null && !it.endsWith(".$extension")) "$it.$extension" else it }
}