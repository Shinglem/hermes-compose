package dev.shinglem.hermes.platform

import java.awt.Desktop
import java.net.URI

actual fun openExternalUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val scheme = trimmed.substringBefore(':', "").lowercase()
    if (scheme !in ALLOWED_SCHEMES) return false
    return runCatching {
        Desktop.getDesktop().browse(URI(trimmed))
        true
    }.getOrDefault(false)
}

private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")
