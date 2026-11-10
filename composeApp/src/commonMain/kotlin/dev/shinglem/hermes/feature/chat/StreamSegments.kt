package dev.shinglem.hermes.feature.chat

import kotlin.time.Instant

/**
 * 流式段落的合并规则（纯函数，便于单测）。
 *
 * 一轮对话的事件序列天然是分段的：`reasoning.delta` → `tool.start/complete` → `message.delta` →
 * `message.interim` → 再来一轮。把它们塞进各自的字段会丢顺序，所以这里一律"写尾部"：
 * 尾部是同类未封段就续写，否则另起一段。
 */

internal fun StreamingState.appendReasoning(text: String): StreamingState {
    if (text.isEmpty()) return this
    val last = segments.lastOrNull()
    val next = if (last is StreamSegment.Reasoning) {
        segments.dropLast(1) + last.copy(text = last.text + text)
    } else {
        segments + StreamSegment.Reasoning(text)
    }
    return copy(segments = next)
}

internal fun StreamingState.appendAnswer(text: String): StreamingState {
    if (text.isEmpty()) return this
    val last = segments.lastOrNull()
    val next = if (last is StreamSegment.Answer && !last.sealed) {
        segments.dropLast(1) + last.copy(text = last.text + text)
    } else {
        segments + StreamSegment.Answer(text)
    }
    return copy(segments = next)
}

/** 工具开始：同一 `tool_id` 重复到达时原位替换，避免卡片出现两次。 */
internal fun StreamingState.startTool(item: ChatItem.Tool): StreamingState {
    val rest = segments.filterNot { it is StreamSegment.Tool && it.item.id == item.id }
    return copy(segments = rest + StreamSegment.Tool(item))
}

internal fun StreamingState.finishTool(toolId: String, result: String?, failed: Boolean): StreamingState =
    copy(
        segments = segments.map { segment ->
            if (segment is StreamSegment.Tool && segment.item.id == toolId) {
                segment.copy(item = segment.item.copy(result = result, failed = failed, running = false))
            } else {
                segment
            }
        },
    )

/**
 * `message.interim`：把尾部正文封成独立一段。
 *
 * 这段文字通常已经通过 `message.delta` 流出（`already_streamed`），所以是**封段**而不是新增；
 * `text` 非空时以它为准（后端给的是这一段最终的文本）。
 */
internal fun StreamingState.sealInterim(text: String): StreamingState {
    val last = segments.lastOrNull()
    val next = when {
        last is StreamSegment.Answer && !last.sealed ->
            segments.dropLast(1) + last.copy(text = text.ifBlank { last.text }, sealed = true)

        text.isBlank() -> segments
        else -> segments + StreamSegment.Answer(text, sealed = true)
    }
    return copy(segments = next)
}

/**
 * `message.complete` 的 `text` 是最后一段正文的权威版本（`transform_llm_output` 可能改写它）。
 *
 * 只替换**尾部未封段**的正文；已封段（interim）和前面几段的正文一律不动 ——
 * 否则「思考A / 工具 / 正文A / …」会被整轮文本覆盖成一条。
 */
internal fun List<StreamSegment>.withFinalText(finalText: String): List<StreamSegment> {
    if (finalText.isBlank()) return this
    val last = lastOrNull()
    return when {
        last is StreamSegment.Answer && !last.sealed -> dropLast(1) + last.copy(text = finalText)
        last is StreamSegment.Answer -> this
        any { it is StreamSegment.Answer && it.text.trim() == finalText.trim() } -> this
        // 工具之后才有最终文本（或整轮没有正文增量）：补成一段，但同样标为已封。
        else -> this + StreamSegment.Answer(finalText, sealed = true)
    }
}

/** 一轮结束：按段落顺序落地成消息条目（思考段保留，与读历史的分段一一对应）。 */
internal fun List<StreamSegment>.toChatItems(
    timestamp: Instant,
    nextId: (String) -> String,
): List<ChatItem> = mapNotNull { segment ->
    when (segment) {
        is StreamSegment.Reasoning -> segment.text.takeIf { it.isNotBlank() }
            ?.let { ChatItem.Reasoning(nextId("reasoning"), timestamp, it) }

        is StreamSegment.Answer -> segment.text.takeIf { it.isNotBlank() }
            ?.let { ChatItem.AssistantMessage(nextId("assistant"), timestamp, it) }

        // 一轮结束后不再有计时意义：没收到 tool.complete 的也不该一直显示"执行中"。
        is StreamSegment.Tool -> segment.item.copy(running = false)
    }
}
