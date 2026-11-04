package dev.shinglem.hermes.feature.chat

import kotlin.time.Instant

/** 消息列表的一条（已把 tool 调用与 tool 结果配对好）。 */
sealed interface ChatItem {
    val id: String
    val timestamp: Instant?

    data class UserMessage(
        override val id: String,
        override val timestamp: Instant?,
        val text: String,
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        override val timestamp: Instant?,
        val markdown: String,
    ) : ChatItem

    data class Tool(
        override val id: String,
        override val timestamp: Instant?,
        val name: String,
        val label: String?,
        val arguments: String,
        val result: String?,
        val failed: Boolean,
        /** 结果来自后续 `role=tool` 行；孤立结果（历史被截断）为 false。 */
        val pairedWithCall: Boolean,
    ) : ChatItem

    data class Notice(
        override val id: String,
        override val timestamp: Instant?,
        val text: String,
    ) : ChatItem

    /**
     * 一轮里某一段思考过程（`reasoning.delta` 聚合而成）。
     *
     * 只有本轮流式结束时才产生 —— 后端不把 reasoning 落进 transcript，
     * 所以重开历史看不到它，这是数据源的限制而不是渲染丢弃。
     */
    data class Reasoning(
        override val id: String,
        override val timestamp: Instant?,
        val text: String,
    ) : ChatItem
}

/** 可复制文本：按 item 决定「复制整条」复制什么。 */
fun ChatItem.copyText(): String = when (this) {
    is ChatItem.UserMessage -> text
    is ChatItem.AssistantMessage -> markdown
    is ChatItem.Tool -> buildString {
        append(name)
        if (arguments.isNotBlank()) append("\n\n").append(arguments)
        if (!result.isNullOrBlank()) append("\n\n").append(result)
    }

    is ChatItem.Notice -> text

    is ChatItem.Reasoning -> text
}
