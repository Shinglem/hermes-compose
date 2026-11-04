package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 状态条两项的文案规则。
 *
 * 回归点：remote 模式下「服务」曾经永远不显示 —— 因为 `serviceLabel` 只在拿到
 * `BackendBootState.Ready.dashboardUrl` 时才渲染，而远端不填这个字段。
 * 现在 remote 用同一 base 探 Web UI（dashboard 的 UI 与网关同端口）。
 */
class BackendStatusTextTest {

    private val remoteReady = BackendBootState.Ready(GatewayTarget("http://127.0.0.1:9119"))
    private val localReady = BackendBootState.Ready(
        GatewayTarget("http://127.0.0.1:57586"),
        dashboardUrl = "http://127.0.0.1:57586",
    )

    @Test
    fun `remote ready shows both gateway and service`() {
        assertEquals("网关 12ms", BackendStatusText.gatewayLabel(BackendKind.REMOTE, remoteReady, ProbeState.Ok(12)))
        assertEquals("服务 12ms", BackendStatusText.serviceLabel(BackendKind.REMOTE, remoteReady, ProbeState.Ok(12)))
    }

    @Test
    fun `service is hidden when not probed`() {
        assertNull(BackendStatusText.serviceLabel(BackendKind.REMOTE, remoteReady, ProbeState.Idle))
    }

    @Test
    fun `local starting shows the step for gateway only`() {
        val starting = BackendBootState.Starting("启动 dashboard")
        assertEquals("网关 启动 dashboard", BackendStatusText.gatewayLabel(BackendKind.LOCAL, starting, ProbeState.Idle))
        // 服务还没起，probe 是 Idle → 不显示，避免出现「服务 —」这种噪声。
        assertNull(BackendStatusText.serviceLabel(BackendKind.LOCAL, starting, ProbeState.Idle))
    }

    @Test
    fun `failed backend marks both chips as failing`() {
        val failed = BackendBootState.Failed("端口被占用")
        assertEquals("网关 失败", BackendStatusText.gatewayLabel(BackendKind.LOCAL, failed, ProbeState.Idle))
        assertEquals("服务 失败", BackendStatusText.serviceLabel(BackendKind.LOCAL, failed, ProbeState.Ok(1)))
        assertEquals(true, BackendStatusText.isGatewayFailing(failed, ProbeState.Idle))
        assertEquals(true, BackendStatusText.isServiceFailing(failed, ProbeState.Ok(1)))
    }

    @Test
    fun `service probe failure is visible`() {
        assertEquals("服务 无响应", BackendStatusText.serviceLabel(BackendKind.LOCAL, localReady, ProbeState.Fail("timeout")))
        assertEquals(true, BackendStatusText.isServiceFailing(localReady, ProbeState.Fail("timeout")))
    }
}
