package dev.shinglem.hermes.feature.editors

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker

/**
 * 系统文件对话框（FileKit，Windows 上走 JNA + COM `IFileOpenDialog`）。
 *
 * 原来用 `java.awt.FileDialog`：它是**模态且阻塞**的，必须丢到 IO 线程，
 * 否则 Compose 的帧循环会跟着停。换 FileKit 之后 `openFilePicker` 本身是挂起函数，
 * 不再需要 `withContext(Dispatchers.IO)`。
 *
 * `runCatching` 保留：对话框初始化失败（COM 不可用 / 被策略禁用）时应当退化成
 * "用户取消"，而不是把异常抛进 UI 侧的事件处理。
 */
actual suspend fun pickExecutableFile(): String? = runCatching {
    FileKit.openFilePicker(
        type = FileKitType.File("exe"),
        dialogSettings = FileKitDialogSettings(title = "选择编辑器可执行文件"),
    )?.file?.absolutePath
}.getOrNull()

actual suspend fun pickJsonFile(): String? = runCatching {
    FileKit.openFilePicker(
        type = FileKitType.File("json"),
        dialogSettings = FileKitDialogSettings(title = "选择会话 JSON 文件"),
    )?.file?.absolutePath
}.getOrNull()