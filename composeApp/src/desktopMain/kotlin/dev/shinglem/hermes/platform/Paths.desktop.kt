package dev.shinglem.hermes.platform

import java.io.File

/**
 * 桌面平台数据根目录：`%LOCALAPPDATA%\hermes-compose`。
 * 日志、缓存、后续的本地索引都落在这里；**不放**仓库与构建产物。
 */
actual val appDataDir: String by lazy {
    val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")
    File(root, "hermes-compose").apply { mkdirs() }.absolutePath
}

actual fun writeBytesToFile(path: String, bytes: ByteArray): Boolean = runCatching {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    true
}.getOrDefault(false)

actual fun readLocalTextFile(path: String): String? = runCatching {
    File(path).readText()
}.getOrNull()
