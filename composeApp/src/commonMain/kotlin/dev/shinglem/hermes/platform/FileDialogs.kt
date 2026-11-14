package dev.shinglem.hermes.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.path

/**
 * 系统文件对话框 —— 全部直接走 FileKit（Windows 上是 JNA + COM `IFileOpenDialog`）。
 *
 * 这里**没有** `expect`/`actual`：FileKit 本身就是 multiplatform，再包一层平台抽象只会
 * 多出两套签名相同的声明。历史实现在 `FileDrop.desktop.kt` / `FilePicker.desktop.kt`。
 *
 * 为什么不用 `java.awt.FileDialog`：它模态且**自己跑事件循环**，而 Tao 进程的 UI 线程是
 * `main`（不是 AWT 的 EDT）—— 在 `Dispatchers.Main` 上弹它会把 Compose 的组合线程阻塞住。
 * FileKit 的 `openFilePicker` / `openFileSaver` 是挂起函数，不占用调用线程。
 */

/** 多选；用户取消返回空列表。 */
suspend fun chooseFiles(title: String, imagesOnly: Boolean): List<String> =
    FileKit.openFilePicker(
        type = if (imagesOnly) FileKitType.Image else FileKitType.File(IMAGE_EXTENSIONS),
        mode = FileKitMode.Multiple(),
        dialogSettings = FileKitDialogSettings(title = title),
    ).orEmpty().map { it.path }

/** 单选；用户取消返回 `null`。 */
suspend fun chooseFile(title: String): String? =
    FileKit.openFilePicker(
        type = FileKitType.File(),
        dialogSettings = FileKitDialogSettings(title = title),
    )?.path

/**
 * 保存对话框；用户取消返回 `null`。
 *
 * 0.17.0 的 `extension` 参数已废弃（它只是"默认扩展名提示"，不是过滤器），
 * 所以把不含扩展名的文件名作为 `suggestedName` 传进去，扩展名由对话框自己截；
 * 用户可能在对话框里删掉扩展名，这里补回去（否则导出的 `tar.gz` 没有后缀）。
 */
suspend fun chooseSavePath(title: String, defaultName: String): String? {
    val suggested = defaultName.substringBeforeLast('.', defaultName)
    val extension = defaultName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
    return FileKit.openFileSaver(
        suggestedName = suggested,
        dialogSettings = FileKitDialogSettings(title = title),
    )?.path
        ?.let { if (extension != null && !it.endsWith(".$extension")) "$it.$extension" else it }
}

/** 选一个可执行文件（编辑器配置）；取消或对话框不可用时返回 `null`。 */
suspend fun chooseExecutable(): String? = runCatching {
    FileKit.openFilePicker(
        type = FileKitType.File("exe"),
        dialogSettings = FileKitDialogSettings(title = "选择编辑器可执行文件"),
    )?.path
}.getOrNull()

/** 选一个 JSON（会话导入）；取消或对话框不可用时返回 `null`。 */
suspend fun chooseJson(): String? = runCatching {
    FileKit.openFilePicker(
        type = FileKitType.File("json"),
        dialogSettings = FileKitDialogSettings(title = "选择会话 JSON 文件"),
    )?.path
}.getOrNull()
