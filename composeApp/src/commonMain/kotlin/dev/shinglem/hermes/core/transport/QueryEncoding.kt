package dev.shinglem.hermes.core.transport

/**
 * 百分号编码（commonMain 没有 `URLEncoder`；只保留 unreserved 字符）。
 *
 * 供各 Repository 拼 `?profile=` 之类的查询串，避免把带空格/中文的值直接塞进 URL。
 */
internal fun encodeQuery(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        val unreserved = code < 0x80 && (char.isLetterOrDigit() || char in "-._~")
        if (unreserved) {
            append(char)
        } else {
            append('%')
            append(hex[(code shr 4) and 0xF])
            append(hex[code and 0xF])
        }
    }
}

/** 给路径追加可选的 `profile` 查询参数（以及额外参数）。 */
internal fun withProfileQuery(path: String, profile: String?, vararg extra: Pair<String, String>): String {
    val params = buildList {
        profile?.takeIf { it.isNotBlank() }?.let { add("profile" to it) }
        addAll(extra)
    }
    if (params.isEmpty()) return path
    val query = params.joinToString("&") { (key, value) -> "$key=${encodeQuery(value)}" }
    return if (path.contains('?')) "$path&$query" else "$path?$query"
}
