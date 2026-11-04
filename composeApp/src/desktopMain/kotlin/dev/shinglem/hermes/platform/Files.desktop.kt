package dev.shinglem.hermes.platform

import java.io.File

actual fun writeTextFile(path: String, text: String): Boolean = runCatching {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(text)
    true
}.getOrDefault(false)

actual val clientVersion: String
    get() = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"
