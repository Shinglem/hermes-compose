package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.transport.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 重连后必须重建会话订阅（#14）。
 *
 * 现场事实：断线重连后旧订阅失效，后端把审批 / clarify 推给旧订阅，本端收不到，
 * 表现为"发消息没反应、刷新才有卡片"。这里用纯函数把判据钉死。
 */
class ReconnectDecisionTest {
    @Test
    fun `首次连上要重新 resume`() {
        assertEquals(ReconnectDecision.ReResume, reconnectDecision(false, ConnectionState.Connected()))
        assertEquals(
            ReconnectDecision.ReResume,
            reconnectDecision(false, ConnectionState.Connected("epoch-1")),
        )
    }

    @Test
    fun `同一次连接里重复的 Connected 被忽略`() {
        // 实测每条连接都会来两次：openSession 里的 Connected() 与 ready 帧里的 Connected(epoch)。
        assertEquals(ReconnectDecision.Ignore, reconnectDecision(true, ConnectionState.Connected()))
        assertEquals(ReconnectDecision.Ignore, reconnectDecision(true, ConnectionState.Connected("epoch-1")))
    }

    @Test
    fun `所有非 Connected 状态都复位`() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting,
            ConnectionState.Reconnecting(attempt = 2, delayMs = 1_000),
            ConnectionState.Disconnected(dev.shinglem.hermes.core.error.AppError.NotConnected),
            ConnectionState.Closed,
        )
        states.forEach { state ->
            assertEquals(ReconnectDecision.Reset, reconnectDecision(true, state), "state=$state")
            assertEquals(ReconnectDecision.Reset, reconnectDecision(false, state), "state=$state")
        }
    }

    @Test
    fun `连上 断开 再连 会再 resume 一次`() {
        val sequence = listOf(
            ConnectionState.Connecting,
            ConnectionState.Connected(),
            ConnectionState.Connected("epoch-1"),
            ConnectionState.Reconnecting(attempt = 1, delayMs = 500),
            ConnectionState.Connected(),
            ConnectionState.Connected("epoch-2"),
            ConnectionState.Closed,
        )
        var connected = false
        val decisions = sequence.map { state ->
            val decision = reconnectDecision(connected, state)
            connected = when (decision) {
                ReconnectDecision.ReResume -> true
                ReconnectDecision.Reset -> false
                ReconnectDecision.Ignore -> connected
            }
            decision
        }
        assertEquals(
            listOf(
                ReconnectDecision.Reset, // Connecting
                ReconnectDecision.ReResume, // 第一次连上
                ReconnectDecision.Ignore, // 同一次连接的第二个 Connected
                ReconnectDecision.Reset, // 断了
                ReconnectDecision.ReResume, // 再连上 —— 必须再 resume
                ReconnectDecision.Ignore,
                ReconnectDecision.Reset,
            ),
            decisions,
        )
    }
}
