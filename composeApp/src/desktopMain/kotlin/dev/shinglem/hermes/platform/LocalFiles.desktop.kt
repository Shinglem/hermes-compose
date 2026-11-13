package dev.shinglem.hermes.platform

import java.io.File

/**
 * 用系统默认程序打开本机文件。
 *
 * 优先 `Desktop.open(File)`（Java 的标准入口）；它失败时回落到
 * `rundll32 url.dll,FileProtocolHandler`（Windows 上对图片、PDF 这类关联更稳）。
 * 两者都失败返回 false。
 */
actual fun openLocalPath(path: String): Boolean {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return false
    val file = File(trimmed)
    if (!file.exists()) return false

    val openedByDesktop = runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(file)
            true
        } else {
            false
        }
    }.getOrDefault(false)
    if (openedByDesktop) return true

    return runCatching {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", file.absolutePath)
            os.contains("mac") -> listOf("open", file.absolutePath)
            else -> listOf("xdg-open", file.absolutePath)
        }
        ProcessBuilder(command).start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        true
    }.getOrDefault(false)
}
