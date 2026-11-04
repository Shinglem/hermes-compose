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
        /**
         * Web UI 地址。Local 模式与 [target] **同端口**（`dashboard` 一个进程同时提供两者）；
         * Remote 模式由远端自己决定，这里不填。
         */
        val dashboardUrl: String? = null,
    ) : BackendBootState

    data class Failed(
        val message: String,
        val detail: String? = null,
    ) : BackendBootState
}

/** 后端来源。 */
enum class BackendKind { LOCAL, REMOTE }
