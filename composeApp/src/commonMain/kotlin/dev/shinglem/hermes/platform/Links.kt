package dev.shinglem.hermes.platform

/**
 * 用系统默认浏览器打开链接。
 *
 * 只接受 `http` / `https` / `mailto`；其它 scheme（`file:`、`javascript:`）一律忽略 ——
 * 它们来自模型输出，不该由客户端替用户执行。
 */
expect fun openExternalUrl(url: String): Boolean
