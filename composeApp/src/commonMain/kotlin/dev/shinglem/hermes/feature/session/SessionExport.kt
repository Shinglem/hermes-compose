package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.feature.chat.ChatItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Instant

/**
 * 会话导出（JSON / Markdown）。
 *
 * 结构与官方桌面端 `lib/session-export.ts` 对齐：`exported_at` / `session_id` / `title` /
 * `session` / `message_count` / `messages`。`session` 我们填 `null` —— 客户端手上只有投影后的
 * 条目，没有原始 session 行；保留这个键是为了让下游脚本的字段判断保持一致。
 *
 * 这里只做纯文本拼装，落盘交给 `pickSaveFile` + `writeTextFile`（平台层）。
 */
object SessionExport {
    fun jsonFileName(title: String?, sessionId: String): String =
        fileName(title, sessionId, "json")

    fun markdownFileName(title: String?, sessionId: String): String =
        fileName(title, sessionId, "md")

    fun toJson(
        sessionId: String,
        title: String?,
        items: List<ChatItem>,
        exportedAt: Instant,
    ): String {
        val root = buildJsonObject {
            put("exported_at", exportedAt.toString())
            put("session_id", sessionId)
            put("title", title)
            put("session", JsonNull)
            put("message_count", items.size)
            putJsonArray("messages") {
                items.forEach { item -> add(item.toJson()) }
            }
        }
        return Pretty.encodeToString(JsonElement.serializer(), root)
    }

    fun toMarkdown(
        sessionId: String,
        title: String?,
        items: List<ChatItem>,
        exportedAt: Instant,
    ): String = buildString {
        append("# ").append(title?.takeIf { it.isNotBlank() } ?: "未命名会话").append("\n\n")
        append("> 会话 `").append(sessionId).append("` · 导出于 ").append(exportedAt.toString())
        append(" · ").append(items.size).append(" 条\n\n")
        items.forEach { item ->
            when (item) {
                is ChatItem.UserMessage -> {
                    append("## 用户\n\n").append(item.text).append("\n\n")
                }

                is ChatItem.AssistantMessage -> {
                    append("## 助手\n\n").append(item.markdown).append("\n\n")
                }

                is ChatItem.Tool -> {
                    append("<details><summary>工具：").append(item.name).append("</summary>\n\n")
                    if (item.arguments.isNotBlank()) {
                        append("```\n").append(item.arguments).append("\n```\n\n")
                    }
                    item.result?.takeIf { it.isNotBlank() }?.let { result ->
                        append("```\n").append(result).append("\n```\n\n")
                    }
                    append("</details>\n\n")
                }

                is ChatItem.Notice -> {
                    append("> ").append(item.text.replace("\n", "\n> ")).append("\n\n")
                }

                is ChatItem.Reasoning -> {
                    append("### 思考\n\n").append(item.text).append("\n\n")
                }
            }
        }
    }

    /**
     * 文件名：`<title>-<id 前 8 位>.<ext>`。
     *
     * 清洗口径与官方一致（小写、非 `[a-z0-9._-]` 折叠成 `-`、去首尾 `-`、截 48 字符）。
     * 纯中文标题清洗后会变空，退回 `session`。
     */
    private fun fileName(title: String?, sessionId: String, extension: String): String {
        val idPart = sanitize(sessionId).take(8).ifBlank { "session" }
        val titlePart = title?.let(::sanitize).orEmpty().ifBlank { "session" }
        return "$titlePart-$idPart.$extension"
    }

    private fun sanitize(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .take(48)

    private fun ChatItem.toJson() = buildJsonObject {
        when (val current = this@toJson) {
            is ChatItem.UserMessage -> {
                put("role", "user")
                put("content", current.text)
            }

            is ChatItem.AssistantMessage -> {
                put("role", "assistant")
                put("content", current.markdown)
            }

            is ChatItem.Tool -> {
                put("role", "tool")
                put("name", current.name)
                put("arguments", current.arguments)
                put("content", current.result)
                put("failed", current.failed)
            }

            is ChatItem.Notice -> {
                put("role", "notice")
                put("content", current.text)
            }

            is ChatItem.Reasoning -> {
                put("role", "reasoning")
                put("content", current.text)
            }
        }
        // 时间戳放在统一位置（没有就省略，而不是写 null 让下游误判）。
        this@toJson.timestamp?.let { instant ->
            put("timestamp", instant.toEpochMilliseconds() / 1000.0)
        }
    }

    private val Pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
