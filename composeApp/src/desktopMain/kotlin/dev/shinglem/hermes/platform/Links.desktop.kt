package dev.shinglem.hermes.platform

import java.net.URI

/**
 * 用系统默认浏览器打开链接。
 *
 * ### 为什么不用 `java.awt.Desktop.browse`（ADR-073）
 *
 * `Desktop.browse` 在 Windows 上要经过 AWT 的桌面集成层，而 S6 之后进程的窗口模型是
 * Tao 的、不是 AWT 的 —— 那层集成在"没有 AWT 窗口"的进程里表现不稳定（部分环境下
 * 抛 `IllegalStateException` 或静默失败）。而它做的事本质上就是 `ShellExecute("open", url)`，
 * 直接用 `rundll32 url.dll,FileProtocolHandler` 走同一条系统路径，不牵扯 AWT。
 *
 * 这也是 `LocalFiles.desktop.kt` 一直在用的兜底写法，行为已经被验证过。
 */
actual fun openExternalUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val scheme = trimmed.substringBefore(':', "").lowercase()
    if (scheme !in ALLOWED_SCHEMES) return false
    // 只接受能解析成合法 URI 的输入：畸形字符串交给 ShellExecute 有参数注入风险
    // （`rundll32` 会把后面的部分当参数）。URL 里本来就该转义，解析失败即拒绝。
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
    if (parsed.scheme.lowercase() !in ALLOWED_SCHEMES) return false

    return runCatching {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", parsed.toASCIIString())
            os.contains("mac") -> listOf("open", parsed.toASCIIString())
            else -> listOf("xdg-open", parsed.toASCIIString())
        }
        ProcessBuilder(command).start().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        true
    }.getOrDefault(false)
}

private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")