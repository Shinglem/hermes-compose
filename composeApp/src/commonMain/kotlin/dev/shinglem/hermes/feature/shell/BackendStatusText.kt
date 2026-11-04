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
 * Local 模式后端是**一个** `dashboard` 进程，同一个端口同时提供 JSON-RPC/REST（网关）
 * 与 Web UI（服务）。两者可以一好一坏 —— payload 缺 `web_dist` 时网关正常、服务是占位页 ——
 * 所以拆成两条独立显示，各自带心跳延迟。
 */
object BackendStatusText {

    /** 网关（JSON-RPC / REST）一项；remote 模式下也显示，方便看出对端是否可达。 */
    fun gatewayLabel(kind: BackendKind, state: BackendBootState, probe: ProbeState): String =
        chip("网关", kind, state, probe)

    /** 服务（Web UI）一项；只有拿得到 Web UI 地址时才显示。 */
    fun serviceLabel(kind: BackendKind, state: BackendBootState, probe: ProbeState): String? =
        if (probe is ProbeState.Idle) null else chip("服务", kind, state, probe)

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

    fun isServiceFailing(state: BackendBootState, probe: ProbeState): Boolean =
        isFailure(state) || probe is ProbeState.Fail

    /** `http://127.0.0.1:57586` → `57586`，解析失败则为 null。 */
    fun port(state: BackendBootState.Ready): Int? =
        state.target.baseUrl.substringAfterLast(':', "").toIntOrNull()

    /** 失败时状态栏与详情里显示的一句话。 */
    fun failureMessage(state: BackendBootState): String? =
        (state as? BackendBootState.Failed)?.message
}
