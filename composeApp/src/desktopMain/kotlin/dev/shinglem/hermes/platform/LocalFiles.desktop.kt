package dev.shinglem.hermes.platform

import java.io.File

/**
 * 用系统默认程序打开本机文件。
 *
 * ### 为什么不用 `java.awt.Desktop.open`（ADR-073）
 *
 * 它是 AWT 桌面集成层的一部分，而 S6 之后进程的窗口模型是 Tao 的。旧实现是
 * "先试 `Desktop.open`，失败再走 `rundll32`" —— 既然兜底那条路本身就能用，
 * 就没必要留着一条会牵扯 AWT 的主路径（而且它失败时往往**不抛异常**，只是返回，
 * 依赖后面的兜底才生效，等于白跑一次）。
 *
 * 现在只走 `rundll32` / `open` / `xdg-open` 这一条：与 `Links.desktop.kt` 打开链接
 * 走同一套机制，行为一致、无 AWT。
 */
actual fun openLocalPath(path: String): Boolean {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return false
    val file = File(trimmed)
    if (!file.exists()) return false

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