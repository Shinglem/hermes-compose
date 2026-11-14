package dev.shinglem.hermes.platform

import java.io.File
import kotlin.io.path.createTempFile

/**
 * 系统剪贴板（Win32 FFM 实现，不经过 AWT）。
 *
 * ### 为什么重写（ADR-075）
 *
 * 旧实现用 `java.awt.Toolkit.systemClipboard`。S6 把窗口壳迁到 Nucleus Tao 之后，
 * `java.desktop` 的桌面集成层不再是本进程的窗口模型，剪贴板成了**最后一个**还需要 AWT
 * 的能力。实测（`clipboardProbe`）确认 Win32 API 在 Tao 的 `main` 线程上完全可用：
 *
 * | 项 | 结果 |
 * |---|---|
 * | `OpenClipboard` 于 Tao `main` | ✅ 一次成功，`lastError=0` |
 * | `CF_UNICODETEXT` 中文往返 | ✅ |
 * | 2560×1440 位图读取 | ✅ 11 ms |
 * | 与 AWT 互操作 | ✅ 双向都通（同一个系统剪贴板） |
 *
 * ### 为什么不换 Compose 的 `ClipboardManager`
 *
 * 它的 desktop 实现 `AwtClipboardManager` 底层就是 `java.awt.datatransfer.Clipboard` ——
 * 换过去只是换个入口，AWT 一点不少；而且它**只有文本**，没有 `hasImage` / 图片读取。
 *
 * 非 Windows 或 FFM 不可用时静默退化成"没有剪贴板"（读返回 null / 写返回失败），
 * 不影响应用启动 —— 本项目只发布 Windows 包。
 */
actual object Clipboard {
    private val api: Win32Clipboard? get() = Win32Clipboard.instance

    actual fun setText(text: String) {
        api?.writeText(text)
    }

    actual fun getText(): String? = api?.readText()

    actual fun hasImage(): Boolean = api?.hasImage() == true

    actual fun saveImageToTemp(): String? {
        val png = api?.readImageAsPng() ?: return null
        return runCatching {
            val file = createTempFile("hermes-clip-", ".png").toFile()
            file.writeBytes(png)
            file.absolutePath
        }.getOrElse {
            dev.shinglem.hermes.core.log.Log.warn("clipboard: cannot persist pasted image: ${it.message}")
            null
        }
    }
}