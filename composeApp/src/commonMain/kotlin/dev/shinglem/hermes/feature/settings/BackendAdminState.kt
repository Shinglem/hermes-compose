package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.error.technicalSummary
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.GatewayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 后端管理页的只读快照（#15）。 */
data class BackendAdminState(
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

    /** 最近若干行日志（诊断与页面共用一份截断规则）。 */
    fun tail(lines: Int = DIAGNOSTIC_LOG_LINES): List<BackendLogLine> = logs.takeLast(lines)

    companion object {
        const val DIAGNOSTIC_LOG_LINES = 40
    }
}

/**
 * 后端管理（#15）：状态、启停、重启、重新解析地址、重连、日志尾、诊断文本。
 *
 * 刻意**不新造抽象**：所有动作都走 [BackendProvider] 与 [GatewayClient] 已有的接口。
 * 全程普通权限 —— 不碰 UAC、不装服务（ADR-056 已否决 SCM 服务化）。
 */
class BackendAdminViewModel(
    private val backend: BackendProvider,
    private val gateway: GatewayClient,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<BackendAdminState> = combine(
        backend.state,
        backend.logs,
        gateway.connection,
        busy,
        notice,
    ) { boot, logs, connection, isBusy, message ->
        BackendAdminState(
            kind = backend.kind,
            boot = boot,
            connection = connection,
            logs = logs,
            busy = isBusy,
            notice = message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        BackendAdminState(kind = backend.kind),
    )

    fun start() = run("启动后端") { backend.start() }

    /** 停止只作用于**本 home**（`gateway stop -y`，绝不 `--all`）。 */
    fun stop() = run("停止后端") { backend.stop() }

    fun restart() = run("重启后端") {
        backend.stop()
        backend.start()
    }

    /** 强制重新解析地址：后端被外部重启换了端口时用它（#14 的自动路径同理）。 */
    fun revalidate() = run("重新解析后端地址") { backend.revalidate() }

    fun reconnect() = run("重连传输层") {
        gateway.stop()
        gateway.start()
    }

    fun dismissNotice() {
        notice.value = null
    }

    /** 复制到剪贴板的诊断文本：状态 + 端口 + 最近日志，**不含任何令牌**。 */
    fun diagnostics(): String {
        val snapshot = state.value
        return buildString {
            appendLine("== Hermes 桌面端 · 后端诊断 ==")
            appendLine("后端来源：${snapshot.kind ?: "未知"}")
            appendLine("启动状态：${describeBoot(snapshot.boot)}")
            appendLine("连接状态：${describeConnection(snapshot.connection)}")
            appendLine("API 地址：${snapshot.apiOrigin ?: "（未就绪）"}")
            appendLine("实例 id：${snapshot.instanceId ?: "—"}")
            appendLine("supervisor：${snapshot.supervisor ?: "—"}")
            appendLine("profile：${snapshot.profileId ?: "—"}")
            appendLine()
            appendLine("== 最近 ${BackendAdminState.DIAGNOSTIC_LOG_LINES} 行后端日志 ==")
            val tail = snapshot.tail()
            if (tail.isEmpty()) {
                appendLine("（无输出）")
            } else {
                tail.forEach { appendLine("[${it.stream}] ${it.text}") }
            }
        }
    }

    private fun run(label: String, action: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            notice.value = null
            try {
                action()
                notice.value = "$label：完成"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.warn("backend admin: $label 失败：${throwable.message}")
                notice.value = "$label 失败：${throwable.message ?: throwable::class.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }
}

internal fun describeBoot(boot: BackendBootState): String = when (boot) {
    BackendBootState.Idle -> "已停止"
    is BackendBootState.Starting -> "启动中：${boot.step}"
    is BackendBootState.Ready -> "运行中"
    is BackendBootState.Failed -> buildString {
        append("启动失败：").append(boot.message)
        boot.detail?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
    }
}

internal fun describeConnection(connection: ConnectionState): String = when (connection) {
    ConnectionState.Idle -> "未连接"
    ConnectionState.Connecting -> "连接中"
    is ConnectionState.Connected -> "已连接"
    is ConnectionState.Reconnecting -> "重连中（第 ${connection.attempt} 次）"
    is ConnectionState.Disconnected -> "已断开：${connection.error.technicalSummary()}"
    ConnectionState.Closed -> "已关闭"
}
