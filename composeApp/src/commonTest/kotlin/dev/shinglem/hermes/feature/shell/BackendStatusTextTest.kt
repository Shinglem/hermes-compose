package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 状态条只有一项：官方 gateway。
 *
 * 历史：「服务」那一项表示 dashboard 的 Web UI 能否打开（payload 缺 `web_dist` 时会「网关正常、
 * 服务无响应」）。Web UI 已不再使用，客户端也不再提供浏览器入口，故只剩网关。
 */
class BackendStatusTextTest {

    private val remoteReady = BackendBootState.Ready(GatewayTarget("http://127.0.0.1:9119"))
    private val localReady = BackendBootState.Ready(GatewayTarget("http://127.0.0.1:57586"))

    @Test
    fun `remote ready shows the probe round trip`() {
        assertEquals("网关 12ms", BackendStatusText.gatewayLabel(BackendKind.REMOTE, remoteReady, ProbeState.Ok(12)))
    }

    @Test
    fun `local starting shows the current step`() {
        val starting = BackendBootState.Starting("连接后端（gateway ensure）")
        assertEquals(
            "网关 连接后端（gateway ensure）",
            BackendStatusText.gatewayLabel(BackendKind.LOCAL, starting, ProbeState.Idle),
        )
    }

    @Test
    fun `failed backend marks the chip as failing`() {
        val failed = BackendBootState.Failed("端口被占用")
        assertEquals("网关 失败", BackendStatusText.gatewayLabel(BackendKind.LOCAL, failed, ProbeState.Idle))
        assertEquals(true, BackendStatusText.isGatewayFailing(failed, ProbeState.Idle))
    }

    @Test
    fun `probe failure is visible`() {
        assertEquals("网关 无响应", BackendStatusText.gatewayLabel(BackendKind.LOCAL, localReady, ProbeState.Fail("timeout")))
        assertEquals(true, BackendStatusText.isGatewayFailing(localReady, ProbeState.Fail("timeout")))
    }

    @Test
    fun `idle probe before ready renders a dash`() {
        assertEquals("网关 —", BackendStatusText.gatewayLabel(BackendKind.LOCAL, localReady, ProbeState.Idle))
    }

    @Test
    fun `port parses the ready target`() {
        assertEquals(57586, BackendStatusText.port(localReady))
    }
}