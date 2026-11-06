package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.transport.GatewayTarget

/**
 * 后端启动状态机（`.design/详细设计/工程与代码/06-本地后端与双模式.md` §3.1）。
 *
 * [Starting] 的 `step` 必须说清卡在哪一步 —— 这条路线上每步失败原因完全不同，
 * 只给一个转圈用户永远定位不到。
 */
sealed interface BackendBootState {
    data object Idle : BackendBootState

    data class Starting(val step: String) : BackendBootState

    data class Ready(
        val target: GatewayTarget,
    ) : BackendBootState

    data class Failed(
        val message: String,
        val detail: String? = null,
    ) : BackendBootState
}

/** 后端来源。 */
enum class BackendKind { LOCAL, REMOTE }
