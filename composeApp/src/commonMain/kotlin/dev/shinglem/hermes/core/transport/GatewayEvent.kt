package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.error.AppError
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement

/** 一条 `method == "event"` 通知（`params.type` / `params.payload` / `session_id` / `seq`）。 */
data class GatewayEvent(
    val type: String,
    val sessionId: String? = null,
    val seq: Long? = null,
    val payload: JsonElement? = null,
    /**
     * 事件帧顶层的 `execution_generation`（不是 payload 里的那个）。
     *
     * `session.interrupt` / `session.steer` / `approval.respond` 都要求它**等于当前执行代**，
     * 而每一轮都会推进它；只靠 `session.resume` 的快照必然过期（`4001 stale_generation`），
     * 所以事件推进时要跟着更新。
     */
    val executionGeneration: Int? = null,
)

/** 本期真正会用到的事件名。完整清单见后端 `contracts/events.py`。 */
object GatewayEventTypes {
    const val READY = "gateway.ready"
    const val SKIN_CHANGED = "skin.changed"
    const val SESSIONS_CHANGED = "sessions.changed"
    const val MESSAGE_START = "message.start"
    const val MESSAGE_DELTA = "message.delta"
    const val MESSAGE_INTERIM = "message.interim"
    const val MESSAGE_COMPLETE = "message.complete"
    const val REASONING_DELTA = "reasoning.delta"
    const val TOOL_START = "tool.start"
    const val TOOL_COMPLETE = "tool.complete"
    const val SESSION_INFO = "session.info"
    const val SESSION_TITLE = "session.title"
    const val SESSION_USAGE = "session.usage"
    const val STATUS_UPDATE = "status.update"
    const val ERROR = "error"

    /** 后端撤回一个等待中的 server→client 请求（审批超时 / 中断）。 */
    const val REQUEST_CANCEL = "request.cancel"

    /** agent 自己的后台进程输出增量（只读镜像 / 后台任务详情）。 */
    const val AGENT_TERMINAL_OUTPUT = "agent.terminal.output"

    // ── v0.3.0 ────────────────────────────────────────────────────────────

    /** 子代理生命周期的六种帧（同一 payload 形状，身份字段可缺）。 */
    const val SUBAGENT_SPAWN_REQUESTED = "subagent.spawn_requested"
    const val SUBAGENT_START = "subagent.start"
    const val SUBAGENT_PROGRESS = "subagent.progress"
    const val SUBAGENT_THINKING = "subagent.thinking"
    const val SUBAGENT_TOOL = "subagent.tool"
    const val SUBAGENT_COMPLETE = "subagent.complete"

    /** 旁路任务（`/background`、`/btw`）完成。 */
    const val BACKGROUND_COMPLETE = "background.complete"
    const val BTW_COMPLETE = "btw.complete"

    /** goal / loop / heartbeat 状态变化。 */
    const val SESSION_CONTROL_UPDATE = "session.control.update"

    val SUBAGENT_EVENTS: Set<String> = setOf(
        SUBAGENT_SPAWN_REQUESTED,
        SUBAGENT_START,
        SUBAGENT_PROGRESS,
        SUBAGENT_THINKING,
        SUBAGENT_TOOL,
        SUBAGENT_COMPLETE,
    )
}

/** 连接状态机：`Idle → Connecting → Connected → (断线) Reconnecting → …`。 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState

    /** 已握手；[replayEpoch] 来自 `gateway.ready`，后端重启后会变。 */
    data class Connected(val replayEpoch: String? = null) : ConnectionState

    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionState
    data class Disconnected(val error: AppError) : ConnectionState
    data object Closed : ConnectionState
}

/** 后端地址 + 凭据。`token` 可为空（无鉴权的本地后端）。 */
data class GatewayTarget(
    val baseUrl: String,
    val token: String? = null,
) {
    /** `http(s)://host:port` → `ws(s)://host:port/api/ws?token=…`。 */
    fun wsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("wss://") || base.startsWith("ws://") -> base
            else -> "ws://$base"
        }
        val credential = token?.takeIf { it.isNotBlank() }
        return if (credential == null) {
            "$wsBase/api/ws"
        } else {
            "$wsBase/api/ws?token=${credential.encodeURLParameter()}"
        }
    }
}
