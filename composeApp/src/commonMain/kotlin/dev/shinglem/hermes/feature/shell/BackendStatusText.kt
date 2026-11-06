package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.ProbeState

/**
 * 状态栏上的后端文案（纯函数，便于单测）。
 *
 * 状态栏只有 28dp，文案必须能一眼区分「正在启动 / 已就绪 / 失败」；
 * 详细信息（失败原因 + 后端输出）在点击后弹出的对话框里。
 *
 * 只有一个后端：官方 gateway。它同时提供 JSON-RPC 与 REST，所以状态栏只有一项。
 * （历史：isolated 路线额外显示「服务 7ms」表示 dashboard 的 Web UI 能否打开；
 * Web UI 已不再使用，见 `CHANGELOG` 0.5.0。）
 */
object BackendStatusText {

    /** 网关（JSON-RPC / REST）一项；remote 模式下也显示，方便看出对端是否可达。 */
    fun gatewayLabel(kind: BackendKind, state: BackendBootState, probe: ProbeState): String =
        chip("网关", kind, state, probe)

    private fun chip(
        name: String,
        kind: BackendKind,
        state: BackendBootState,
        probe: ProbeState,
    ): String = when (state) {
        BackendBootState.Idle ->
            if (kind == BackendKind.LOCAL) "$name 准备中" else "$name ${probeText(probe)}"

        is BackendBootState.Starting ->
            if (kind == BackendKind.LOCAL) "$name ${state.step}" else "$name ${probeText(probe)}"

        is BackendBootState.Ready -> "$name ${probeText(probe)}"

        is BackendBootState.Failed -> "$name 失败"
    }

    private fun probeText(probe: ProbeState): String = when (probe) {
        ProbeState.Idle -> "—"
        is ProbeState.Ok -> "${probe.rttMs}ms"
        is ProbeState.Fail -> "无响应"
    }

    fun isFailure(state: BackendBootState): Boolean = state is BackendBootState.Failed

    fun isGatewayFailing(state: BackendBootState, probe: ProbeState): Boolean =
        isFailure(state) || probe is ProbeState.Fail

    /** `http://127.0.0.1:57586` → `57586`，解析失败则为 null。 */
    fun port(state: BackendBootState.Ready): Int? =
        state.target.baseUrl.substringAfterLast(':', "").toIntOrNull()

    /** 失败时状态栏与详情里显示的一句话。 */
    fun failureMessage(state: BackendBootState): String? =
        (state as? BackendBootState.Failed)?.message
}
