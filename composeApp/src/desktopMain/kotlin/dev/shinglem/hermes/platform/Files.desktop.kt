package dev.shinglem.hermes.platform

import java.io.File

actual fun writeTextFile(path: String, text: String): Boolean = runCatching {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(text)
    true
}.getOrDefault(false)

actual fun readFileBytes(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    // 图片附加的上限（后端 `_ATTACH_BYTES_MAX_BYTES` 同量级）：再大就明确失败，
    // 不要把一个 200MB 的文件读进内存再被后端拒绝。
    if (file.length() > MAX_ATTACH_BYTES) return@runCatching null
    file.readBytes()
}.getOrNull()

private const val MAX_ATTACH_BYTES = 32L * 1024 * 1024

actual val clientVersion: String
    get() = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"
