package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlin.time.Clock

/** 流式工具条目的 id 前缀（与历史里的区分开，见 `ChatItem.Tool`）。 */
internal const val LIVE_TOOL_PREFIX = "live:"

/**
 * 把一条流式事件应用到一个**不在前台**的会话状态（"影子状态"）。
 *
 * 为什么需要：切走时只标记 dirty、等切回来补拉并不够 —— 切回时那轮往往还在跑
 * （审批就是在等它），`isSessionLive` 为真就跳过补拉，而切走期间的 `message.delta`
 * 早已被丢弃。结果界面永久停在切走前那一帧，必须手动点"刷新"（用户实测复现）。
 *
 * 这里只做纯数据变换：不碰 `_state`、不发通知、不做 40ms 合并（没有重组压力）。
 */
internal fun ChatState.applyShadowEvent(
    parsed: ChatStreamEvent,
    nextId: (String) -> String = { kind -> "shadow:$kind" },
    now: kotlin.time.Instant = Clock.System.now(),
): ChatState {
    fun streamingOrNew() = streaming ?: StreamingState(startedAt = now)
    return when (parsed) {
        is ChatStreamEvent.Delta ->
            copy(streaming = streamingOrNew().appendAnswer(parsed.text))

        is ChatStreamEvent.ReasoningDelta ->
            copy(streaming = streamingOrNew().appendReasoning(parsed.text))

        ChatStreamEvent.TurnStarted -> copy(streaming = streamingOrNew())

        is ChatStreamEvent.Interim ->
            copy(streaming = streamingOrNew().sealInterim(parsed.text))

        is ChatStreamEvent.ToolStarted -> copy(
            streaming = streamingOrNew().startTool(
                ChatItem.Tool(
                    id = LIVE_TOOL_PREFIX + parsed.toolId.ifBlank { nextId("tool") },
                    timestamp = now,
                    name = parsed.name,
                    label = parsed.label,
                    arguments = parsed.arguments,
                    result = null,
                    failed = false,
                    pairedWithCall = true,
                    running = true,
                ),
            ),
        )

        is ChatStreamEvent.ToolCompleted -> copy(
            streaming = streaming?.finishTool(
                toolId = LIVE_TOOL_PREFIX + parsed.toolId,
                result = parsed.result,
                failed = parsed.failed,
            ),
        )

        is ChatStreamEvent.Completed -> {
            val segments = streaming?.segments.orEmpty().withFinalText(parsed.text)
            val additions = segments.toChatItems(now, nextId)
            copy(
                load = Loadable.Ready(load.valueOrNull.orEmpty() + additions),
                streaming = null,
            )
        }

        else -> this
    }
}
