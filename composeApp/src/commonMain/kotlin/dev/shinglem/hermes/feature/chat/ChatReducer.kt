package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 把一条流式事件应用到 [ChatState]（纯函数）。
 *
 * ### 为什么要有这个文件
 *
 * 迁移前「同一套流式变换」在**两处**各写了一遍：
 * 1. `ChatViewModel` 的前台路径（`appendReasoning` / `sealInterim` / `upsertTool` /
 *    `finishTool` / `finishTurn`）；
 * 2. `SessionShadow.applyShadowEvent`（切走的会话在后台累积）。
 *
 * 两条路径的差异**只在副作用**（前台要走 40ms 合并缓冲、要刷新用量、要发 `ChatEffect`），
 * 数据变换本身一模一样 —— 两份实现漂移是真出过 bug 的来源（一处补了「本轮没有返回任何内容」
 * 的提示，另一处没补，于是切走再切回时那一轮的失败提示会消失）。
 *
 * 现在两处都调这一个函数；前台侧额外的 `deltaBuffer` 合并留在调用方（那是"何时写 state"
 * 的策略，不是变换）。
 *
 * @param nextId 本地 id 生成器（`notice` / `tool` 之类）。**调用方持有序号**，
 *   因为同一个 `ChatViewModel` 里的前台与后台路径要共用一套递增序列。
 * @param now 时间戳来源；测试传固定值以免断言抖动。
 */
internal fun ChatState.reduceStreamEvent(
    parsed: ChatStreamEvent,
    nextId: (String) -> String,
    now: Instant = Clock.System.now(),
): ChatState {
    fun streamingOrNew() = streaming ?: StreamingState(startedAt = now)

    return when (parsed) {
        is ChatStreamEvent.Delta ->
            if (parsed.text.isEmpty()) this else copy(streaming = streamingOrNew().appendAnswer(parsed.text))

        is ChatStreamEvent.ReasoningDelta ->
            if (parsed.text.isEmpty()) this else copy(streaming = streamingOrNew().appendReasoning(parsed.text))

        ChatStreamEvent.TurnStarted -> copy(streaming = streamingOrNew())

        is ChatStreamEvent.Interim -> copy(streaming = streamingOrNew().sealInterim(parsed.text))

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

        is ChatStreamEvent.Completed -> finishTurn(parsed, nextId, now)

        else -> this
    }
}

/** 一轮结束：把流式段落固化成一串 [ChatItem] 塞进 `load`，并清掉 `streaming`。 */
private fun ChatState.finishTurn(
    completed: ChatStreamEvent.Completed,
    nextId: (String) -> String,
    now: Instant,
): ChatState {
    val segments = streaming?.segments.orEmpty().withFinalText(completed.text)
    // 只在本轮**真的在本地跑过**（有 streaming）时才判定"什么都没返回"。
    // 无 streaming 时收到 complete 说明这一轮的内容早已在历史里（重连后第一条就是 complete
    // 的情形），这时补一条"没有返回任何内容"纯属噪音 —— 这条判据是两个平行实现漂移的产物，
    // 前台那份一直会补、影子那份不补，现在统一成"不补"。
    val hadLocalStreaming = streaming != null
    // 后端跑完却什么都没给（实测 `response=0 chars`：上游断流后被吞掉）：
    // 不提示的话界面上就是"发了消息、什么都没发生"（#14）。
    val hasVisible = segments.any { segment ->
        when (segment) {
            is StreamSegment.Answer -> segment.text.isNotBlank()
            is StreamSegment.Reasoning -> segment.text.isNotBlank()
            is StreamSegment.Tool -> true
        }
    }
    val additions = buildList {
        addAll(segments.toChatItems(now) { kind -> nextId(kind) })
        // 失败的一轮要在对话里留痕：只放顶部横幅的话，滚动回来看历史会以为"什么都没发生"。
        if (completed.failed) {
            val reason = completed.error?.takeIf { it.isNotBlank() }
                ?: completed.status?.takeIf { it.isNotBlank() }
                ?: "这一轮没有正常结束"
            add(ChatItem.Notice(nextId("notice"), now, "本轮失败：$reason"))
        } else if (!hasVisible && hadLocalStreaming) {
            add(
                ChatItem.Notice(
                    nextId("notice"),
                    now,
                    "本轮没有返回任何内容（上游可能中断），可以重试。",
                ),
            )
        }
    }
    val merged = load.valueOrNull.orEmpty() + additions
    return copy(
        load = Loadable.Ready(merged),
        streaming = null,
        sendError = if (completed.failed) {
            AppError.Server(0, completed.error ?: completed.status.orEmpty().ifBlank { "turn failed" })
        } else {
            sendError
        },
    )
}
