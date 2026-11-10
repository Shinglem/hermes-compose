package dev.shinglem.hermes.core.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `exit 75` 的接管判据（#13）。
 *
 * 现场原文（`desktop.log`，2026-11-10 10:41:12）：
 * ```
 * gateway attach: 无法访问后端运行时 — reason=runtime_exited —
 *   the gateway exited with status 75 before it could serve:
 *   ⏳ A gateway already owns this host and has not published its served set yet.
 *      PID 21608 (launched by profile 'default'; serves: not published yet
 *      (its control socket has not answered))
 *      ... Take the host over (only from the home that launched it):  hermes gateway run --replace
 * ```
 * 只认这一组特征，别把"正在启动"也当成"半死"去替换。
 */
class GatewayEnsureOccupancyTest {
    private fun unavailable(reasonCode: String?, detail: String?) =
        EnsureVerdict.Unavailable(state = "starting", reasonCode = reasonCode, detail = detail)

    private val realDetail = """
        the gateway exited with status 75 before it could serve:
        ⏳ A gateway already owns this host and has not published its served set yet.
           PID 21608 (launched by profile 'default'; serves: not published yet (its control socket has not answered))
           Take the host over (only from the home that launched it):  hermes gateway run --replace
    """.trimIndent()

    @Test
    fun `实测那段 detail 被识别为可接管`() {
        assertTrue(GatewayEnsure.isStaleHostOccupancy(unavailable("runtime_exited", realDetail)))
    }

    @Test
    fun `control socket 未应答也算`() {
        val detail = "A gateway already owns this host; its control socket has not answered"
        assertTrue(GatewayEnsure.isStaleHostOccupancy(unavailable("runtime_exited", detail)))
    }

    @Test
    fun `reason_code 不是 runtime_exited 一律不接管`() {
        assertFalse(GatewayEnsure.isStaleHostOccupancy(unavailable("deadline", realDetail)))
        assertFalse(GatewayEnsure.isStaleHostOccupancy(unavailable("authorization", realDetail)))
        assertFalse(GatewayEnsure.isStaleHostOccupancy(unavailable("profile_mismatch", realDetail)))
        assertFalse(GatewayEnsure.isStaleHostOccupancy(unavailable(null, realDetail)))
    }

    @Test
    fun `detail 缺关键特征时不接管`() {
        assertFalse(
            GatewayEnsure.isStaleHostOccupancy(
                unavailable("runtime_exited", "the gateway exited with status 1 before it could serve"),
            ),
        )
        assertFalse(GatewayEnsure.isStaleHostOccupancy(unavailable("runtime_exited", null)))
        // 只说"被占用"但没给接管线索：可能是别的 profile 的 owner，不能动。
        assertFalse(
            GatewayEnsure.isStaleHostOccupancy(
                unavailable("runtime_exited", "A gateway already owns this host."),
            ),
        )
    }

    @Test
    fun `takeover 命令是 gateway run --replace 且带短超时`() {
        val spec = GatewayAttachPlanner.takeoverSpec(
            payloadDir = "C:/payload",
            homeDir = "C:/home",
            token = "tok",
            local = dev.shinglem.hermes.core.config.LocalSection(),
            osEnv = emptyMap(),
        )
        assertTrue(spec.args.containsAll(listOf("gateway", "run", "--replace")))
        assertTrue(spec.timeoutMs == GatewayAttachPlanner.TAKEOVER_TIMEOUT_MS)
        assertTrue(spec.env["HERMES_HOME"] == "C:/home")
    }
}
