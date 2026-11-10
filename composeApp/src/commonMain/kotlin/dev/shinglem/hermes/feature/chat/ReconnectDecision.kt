package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.transport.ConnectionState

/**
 * 连接状态迁移后该做什么（纯函数，便于单测）。
 *
 * 背景（#14）：会话订阅和"本端会应答 server→client 请求"的声明都绑在**连接**上。
 * 断线重连后旧订阅作废，必须重新 `session.resume`，否则后端把审批 / clarify 推给旧订阅，
 * 本端永远收不到 —— 表现就是"发消息没反应、刷新才有卡片"。
 *
 * 两个坑：
 * 1. `Connected` 会来**两次**（`openSession` 里的 `Connected()` 与 `ready` 帧里的
 *    `Connected(epoch)`），只应处理一次；
 * 2. 中间的 `Reconnecting` / `Disconnected` 必须复位，否则"连上→断→再连"会被当成同一次连接。
 */
internal enum class ReconnectDecision {
    /** 刚从"未连接"变成"连接"：清掉本端的 runtime/fence 缓存并重新 resume。 */
    ReResume,

    /** 同一次连接里重复的 `Connected`：忽略。 */
    Ignore,

    /** 断开或未连接：复位标记，下次 `Connected` 要重新 resume。 */
    Reset,
}

internal fun reconnectDecision(connectedBefore: Boolean, state: ConnectionState): ReconnectDecision =
    when (state) {
        is ConnectionState.Connected -> if (connectedBefore) ReconnectDecision.Ignore else ReconnectDecision.ReResume
        else -> ReconnectDecision.Reset
    }
