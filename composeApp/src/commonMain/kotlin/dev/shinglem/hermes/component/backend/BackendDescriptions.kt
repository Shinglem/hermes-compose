package dev.shinglem.hermes.component.backend

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.error.technicalSummary
import dev.shinglem.hermes.core.transport.ConnectionState

/**
 * 启动状态的**人话**描述（诊断文本与 UI 共用）。
 *
 * 放在 Component 层而不是 UI：诊断文本由 Executor 生成、由 UI 复制，
 * 两边都要用同一份规则，放 UI 会导致 Executor 反向依赖 UI。
 */
fun describeBoot(boot: BackendBootState): String = when (boot) {
    BackendBootState.Idle -> "已停止"
    is BackendBootState.Starting -> "启动中：${boot.step}"
    is BackendBootState.Ready -> "运行中"
    is BackendBootState.Failed -> buildString {
        append("启动失败：").append(boot.message)
        boot.detail?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
    }
}

fun describeConnection(connection: ConnectionState): String = when (connection) {
    ConnectionState.Idle -> "未连接"
    ConnectionState.Connecting -> "连接中"
    is ConnectionState.Connected -> "已连接"
    is ConnectionState.Reconnecting -> "重连中（第 ${connection.attempt} 次）"
    is ConnectionState.Disconnected -> "已断开：${connection.error.technicalSummary()}"
    ConnectionState.Closed -> "已关闭"
}