package dev.shinglem.hermes.feature.chat

/**
 * 落库行 → 可渲染条目。
 *
 * 规则（与后端 `session_history` 的投影语义对齐）：
 * - `assistant` 的文本与它的 `tool_calls` 各自成条；调用的结果取自后续 `tool` 行（按 `tool_call_id` 配对）。
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
            ROLE_USER -> if (row.text.isNotBlank()) {
                items += ChatItem.UserMessage(row.id, row.timestamp, row.text)
            }

            ROLE_ASSISTANT -> {
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
