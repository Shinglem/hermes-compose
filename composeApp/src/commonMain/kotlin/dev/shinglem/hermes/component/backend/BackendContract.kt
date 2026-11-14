package dev.shinglem.hermes.component.backend

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.transport.ConnectionState

/** 后端管理页的只读快照（S5：从 `BackendAdminState` 迁来）。 */
@Immutable
data class BackendState(
    val kind: BackendKind? = null,
    val boot: BackendBootState = BackendBootState.Idle,
    val connection: ConnectionState = ConnectionState.Idle,
    val logs: List<BackendLogLine> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
) {
    /** 当前后端地址。**不含令牌** —— 令牌永远不进 UI，也不进诊断文本。 */
    val apiOrigin: String? get() = (boot as? BackendBootState.Ready)?.target?.baseUrl

    val instanceId: String? get() = (boot as? BackendBootState.Ready)?.instanceId
    val supervisor: String? get() = (boot as? BackendBootState.Ready)?.supervisor
    val profileId: String? get() = (boot as? BackendBootState.Ready)?.profileId
    val running: Boolean get() = boot is BackendBootState.Ready

    fun tail(lines: Int = DIAGNOSTIC_LOG_LINES): List<BackendLogLine> = logs.takeLast(lines)

    companion object {
        const val DIAGNOSTIC_LOG_LINES = 40
    }
}

/**
 * 后端管理页的动作。
 *
 * 五个动作全部走 [dev.shinglem.hermes.core.backend.BackendProvider] /
 * [dev.shinglem.hermes.core.transport.GatewayClient] 已有的接口，**不新造抽象**
 * （ADR-056 已否决 SCM 服务化）。
 */
sealed interface BackendIntent {
    /**
     * 开始订阅外部流（幂等）。DefaultBackendComponent 创建时自发一次。
     *
     * 必须显式：MVIKotlin 的 Executor 在构造期不能 dispatch（Store 还没就绪）。
     */
    data object Observe : BackendIntent

    data object Start : BackendIntent
    data object Stop : BackendIntent
    data object Restart : BackendIntent

    /** 强制重新解析地址：后端被外部重启换了端口时用它（#14 的自动路径同理）。 */
    data object Revalidate : BackendIntent

    data object Reconnect : BackendIntent
    data object DismissNotice : BackendIntent

    /** 把诊断文本写到剪贴板（UI 侧拿这个字符串，不含令牌）。 */
    data object CopyDiagnostics : BackendIntent
}

sealed interface BackendEffect {
    /** 请求把这段文本放进剪贴板。 */
    data class CopyToClipboard(val text: String) : BackendEffect
}