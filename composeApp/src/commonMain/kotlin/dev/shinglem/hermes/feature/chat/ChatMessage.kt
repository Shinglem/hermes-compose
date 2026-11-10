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
        /**
         * 这一步**正在执行**（还没收到 `tool.complete`）。
         *
         * 只有流式路径会置 true；卡片据此显示已执行时长，让"长命令在跑"与"界面死了"可区分。
         */
        val running: Boolean = false,
    ) : ChatItem

    data class Notice(
        override val id: String,
        override val timestamp: Instant?,
        val text: String,
    ) : ChatItem

    /**
     * 一段思考过程。
     *
     * 两个来源：流式的 `reasoning.delta` 聚合，以及读历史时每步 assistant 行自带的
     * `reasoning`。后端**每步一条 assistant 行各自带一份**，所以重开历史是按步分段的，
     * 与实时看到的分段一一对应。
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
