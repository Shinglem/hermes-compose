package dev.shinglem.hermes.feature.editors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 工具调用里出现的一个文件。 */
data class ToolFileRef(
    /** 已归一化的绝对路径（去不掉时保留原样）。 */
    val path: String,
    /** 1 基行号；工具参数里没有行号信息时为 null。 */
    val line: Int? = null,
)

/**
 * 从工具名 + `arguments` JSON 里取出涉及的文件。
 *
 * 只认后端真实存在的文件类工具（`hermes-agent/tools/file_tools.py`）：
 * `write_file` / `read_file` / `patch`。别的一律返回空 —— 宁可少一个按钮，
 * 也不要从 `terminal` 的字符串里正则猜路径（误判率高得多）。
 *
 * `patch` 的 V4A 模式（`mode=patch` + `patch` 参数）一次改多个文件，
 * 文件路径写在 `*** Update File:` / `*** Add File:` / `*** Delete File:` 后面。
 */
fun toolFileRefs(toolName: String, argumentsJson: String, cwd: String?, userHome: String): List<ToolFileRef> {
    if (argumentsJson.isBlank()) return emptyList()
    val json = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return emptyList()
    return when (toolName) {
        "write_file", "read_file", "patch" -> {
            val direct = json.stringOrNull("path")
            if (!direct.isNullOrBlank()) {
                listOf(ToolFileRef(normalizeToolPath(direct, cwd, userHome) ?: return emptyList()))
            } else {
                // patch 的 V4A 模式：从 patch 正文里抄出所有文件
                json.stringOrNull("patch")
                    ?.let { v4aPaths(it) }
                    ?.mapNotNull { raw -> normalizeToolPath(raw, cwd, userHome)?.let(::ToolFileRef) }
                    .orEmpty()
            }
        }

        else -> emptyList()
    }
}

/** `*** Update File: a/b.kt` / `*** Add File: …` / `*** Delete File: …` */
internal fun v4aPaths(patch: String): List<String> =
    patch.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            V4A_PREFIXES.firstOrNull { trimmed.startsWith(it) }
                ?.let { trimmed.removePrefix(it).trim() }
                ?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .toList()

private val V4A_PREFIXES = listOf("*** Update File:", "*** Add File:", "*** Delete File:", "*** Move to:")

/**
 * 归一化成绝对路径。
 *
 * - `~/x` → `<userHome>/x`；
 * - 绝对路径原样（只统一分隔符）；
 * - 相对路径拼会话 cwd；**没有 cwd 就返回 null**（不猜当前进程目录，那几乎总是错的）。
 */
fun normalizeToolPath(raw: String, cwd: String?, userHome: String): String? {
    val path = raw.trim().trim('"', '\'').replace('\\', '/')
    if (path.isEmpty()) return null
    if (path == "~") return userHome.replace('\\', '/').trimEnd('/')
    if (path.startsWith("~/")) {
        val home = userHome.replace('\\', '/').trimEnd('/')
        return if (home.isEmpty()) null else "$home/${path.removePrefix("~/")}"
    }
    if (isAbsoluteLike(path)) return path
    val base = cwd?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    return "$base/$path"
}

/** `C:/x`、`/x`、`//server/share/x` 都算绝对。 */
private fun isAbsoluteLike(path: String): Boolean =
    (path.length >= 2 && path[1] == ':') || path.startsWith("/")

/** 父目录（给「打开所在文件夹」用）；path 本身是根时返回 null。 */
fun parentDirOf(path: String): String? {
    val normalized = path.replace('\\', '/').trimEnd('/')
    val index = normalized.lastIndexOf('/')
    if (index <= 0) return null
    val parent = normalized.substring(0, index)
    // `C:/a.kt` 的父目录是盘符根 `C:/`（资源管理器能打开），补回分隔符
    return if (parent.length == 2 && parent[1] == ':') "$parent/" else parent
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
