package dev.shinglem.hermes.core.backend

/**
 * 一次后端心跳探测的结果。
 *
 * `gateway`（JSON-RPC/REST 同端口）与 `dashboard`（Web UI 独立端口）分开探：
 * 两者是两个进程，一个死了另一个可能还活着 —— 状态栏必须能区分。
 */
sealed interface ProbeState {
    data object Idle : ProbeState

    /** [rttMs] 是 `/api/status` 的往返毫秒数。 */
    data class Ok(val rttMs: Long) : ProbeState

    data class Fail(val reason: String) : ProbeState
}
