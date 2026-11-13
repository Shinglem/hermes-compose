package dev.shinglem.hermes.feature.chat

/**
 * 落库行 → 可渲染条目。
 *
 * 规则（与后端 `session_history` 的投影语义对齐）：
 * - `assistant` 的思考（`reasoning`）、文本与它的 `tool_calls` 各自成条，顺序是
 *   **思考 → 正文 → 工具**（与流式观感一致）；调用的结果取自后续 `tool` 行（按 `tool_call_id` 配对）。
 * - 已配对的 `tool` 行不再单独成条，避免工具卡片出现两次。
 * - 找不到调用方的 `tool` 行（历史被截断 / 老数据）单独成卡片，标为"孤立结果"。
 * - `hidden`（`display_kind == "hidden"`）整行跳过。
 * - assistant 行的 `finish_reason` 异常（`length` / `content_filter` / `incomplete`）时，
 *   在正文后补一条 [ChatItem.Notice]，否则重开历史就是"回答莫名其妙断了"。
 */
fun projectChat(rows: List<ChatRow>): List<ChatItem> {
    val resultsByCallId = rows
        .filter { it.role == ROLE_TOOL && it.toolCallId != null }
        .associateBy { it.toolCallId }

    val knownCallIds = buildSet {
        rows.forEach { row -> row.toolCalls.forEach { add(it.id) } }
    }

    val items = ArrayList<ChatItem>(rows.size)
    rows.forEach { row ->
        if (row.hidden) return@forEach

        when (row.role) {
            ROLE_USER -> {
                // 正文里的 `@image:<path>` 行是后端对附件的记法：拆出来变成 chip，
                // 否则重开历史只能看到一行没意义的路径（#20）。
                val (text, attachments) = splitUserAttachments(row.text)
                if (text.isNotBlank() || attachments.isNotEmpty()) {
                    items += ChatItem.UserMessage(row.id, row.timestamp, text, attachments)
                }
            }

            ROLE_ASSISTANT -> {
                // 顺序必须与流式一致（思考 → 正文 → 工具），否则重开后观感与实时不同。
                row.reasoning?.takeIf { it.isNotBlank() }?.let { text ->
                    items += ChatItem.Reasoning("$REASONING_ITEM_PREFIX${row.id}", row.timestamp, text)
                }
                if (row.text.isNotBlank()) {
                    items += ChatItem.AssistantMessage(row.id, row.timestamp, row.text)
                }
                row.toolCalls.forEach { call ->
                    val resultRow = resultsByCallId[call.id]
                    items += ChatItem.Tool(
                        id = "$TOOL_ITEM_PREFIX${call.id}",
                        timestamp = row.timestamp,
                        name = call.name,
                        label = row.labels[call.id]?.firstOrNull()?.text?.takeIf { it.isNotBlank() },
                        arguments = call.arguments,
                        result = resultRow?.text,
                        failed = resultRow?.text?.let(::looksLikeFailure) ?: false,
                        pairedWithCall = true,
                    )
                }
                truncationNotice(row)?.let { items += it }
            }

            ROLE_TOOL -> {
                val callId = row.toolCallId
                if (callId != null && callId in knownCallIds) return@forEach
                items += ChatItem.Tool(
                    id = "$ROW_ITEM_PREFIX${row.id}",
                    timestamp = row.timestamp,
                    name = row.toolName ?: ROLE_TOOL,
                    label = null,
                    arguments = "",
                    result = row.text,
                    failed = looksLikeFailure(row.text),
                    pairedWithCall = false,
                )
            }

            else -> if (row.text.isNotBlank()) {
                items += ChatItem.Notice(row.id, row.timestamp, row.text)
            }
        }
    }
    return items
}

/** 这一轮的 `finish_reason` 是否属于"回答没正常结束"（要提示用户）。 */
internal fun abnormalFinishNote(finishReason: String?): String? = when (finishReason?.lowercase()) {
    "length" -> "回答达到输出长度上限，被截断了。"
    "content_filter" -> "回答被提供方的内容策略截断。"
    "incomplete" -> "回答未完成（提供方提前结束）。"
    else -> null
}

/** 用户消息里的一条附件引用（从后端落库正文里解析出来的）。 */
data class UserAttachment(
    /** 绝对路径（后端 `@image:` 行里的那个）。 */
    val path: String,
    /** 显示用文件名。 */
    val name: String,
)

/**
 * 从后端落库的 user 正文里拆出附件引用（#20）。
 *
 * 后端把图注记成两种行：`@image:<绝对路径>`（引用本行之上的一条媒体）与紧随其后的
 * `[screenshot]` 之类占位标记。界面上不该原样显示这两行 —— 它们对用户没有意义，
 * 而应该变成一条可点击的附件 chip。
 *
 * 返回的正文已去掉这两类行；
 * 解析失败的路径（空串）与重复项都会被丢弃。
 */
internal fun splitUserAttachments(text: String): Pair<String, List<UserAttachment>> {
    if (!text.contains('@')) return text.trimEnd() to emptyList()
    val kept = ArrayList<String>(8)
    val attachments = ArrayList<UserAttachment>(4)
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.startsWith(IMAGE_REF_PREFIX)) {
            val path = line.removePrefix(IMAGE_REF_PREFIX).trim().trim('"', '\'')
            if (path.isNotEmpty()) {
                attachments += UserAttachment(path = path, name = path.substringAfterLast('\\').substringAfterLast('/'))
            }
            continue
        }
        // `[screenshot]` / `[image]` 之类占位标记：信息已经由上面那条 chip 表达
        if (line in MEDIA_PLACEHOLDERS) continue
        kept += rawLine
    }
    return kept.joinToString("\n").trim() to attachments.distinctBy { it.path }
}

private const val IMAGE_REF_PREFIX = "@image:"
private val MEDIA_PLACEHOLDERS = setOf("[screenshot]", "[image]", "[attached image]")

/** 取文件名；路径里没有分隔符时原样返回（供界面显示用）。 */
internal fun String.fileNameOrPath(): String =
    substringAfterLast('\\').substringAfterLast('/').ifBlank { this }

private fun truncationNotice(row: ChatRow): ChatItem.Notice? {
    val note = abnormalFinishNote(row.finishReason) ?: return null
    return ChatItem.Notice("$NOTICE_ITEM_PREFIX${row.id}", row.timestamp, note)
}

/** 工具结果没有统一状态字段，只能看文本特征；宁可漏判也不误报。 */
internal fun looksLikeFailure(text: String): Boolean {
    val head = text.trimStart().take(64).lowercase()
    return head.startsWith("error:") ||
        head.startsWith("error\n") ||
        head.startsWith("traceback (most recent call last)") ||
        head.startsWith("{\"error\"")
}

internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"
internal const val ROLE_TOOL = "tool"

private const val TOOL_ITEM_PREFIX = "call:"
private const val ROW_ITEM_PREFIX = "tool:"
private const val NOTICE_ITEM_PREFIX = "notice:"
private const val REASONING_ITEM_PREFIX = "reason:"
