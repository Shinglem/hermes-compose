package dev.shinglem.hermes.component.backend.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.backend.BackendEffect
import dev.shinglem.hermes.component.backend.BackendGateway
import dev.shinglem.hermes.component.backend.BackendIntent
import dev.shinglem.hermes.component.backend.BackendResult
import dev.shinglem.hermes.component.backend.BackendState
import dev.shinglem.hermes.component.backend.describeBoot
import dev.shinglem.hermes.component.backend.describeConnection
import dev.shinglem.hermes.core.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 后端管理页的执行器。
 *
 * 两条职责：
 * 1. **初始化时订阅**三条外部流并合并成快照（迁移前是 `combine(...).stateIn(...)`）；
 * 2. 执行 5 个动作，期间维护 `busy` 与 `notice`。
 *
 * 迁移前 `busy` / `notice` 是两个独立 `MutableStateFlow` 再 `combine` 进最终状态；
 * 现在它们是 Store 状态的一部分，Reducer 统一合并 —— 少了两条流之间的时序窗口
 * （那个窗口里"busy 已经 true 但 notice 还是旧的"）。
 */
internal class BackendExecutor(
    private val gateway: BackendGateway,
    executorContext: CoroutineContext,
) : CoroutineExecutor<BackendIntent, Nothing, BackendState, BackendResult, BackendEffect>(executorContext) {

    private var subscribed = false

    /**
     * 订阅三条外部流（**懒启动**）。
     *
     * 不能在 `init` 里做：MVIKotlin 的 Executor 构造时 Store 还没初始化完，
     * 此时 `dispatch` 会抛 `IllegalArgumentException: Value was not initialized`。
     * 第一次 `executeIntent` 时才开始订阅，语义上等价（Component 一创建 UI 就会
     * 发一次 `Observe`）。
     */
    private fun subscribeOnce() {
        if (subscribed) return
        subscribed = true
        combine(
            gateway.boot,
            gateway.logs,
            gateway.connection,
        ) { boot, logs, connection -> snapshot(boot, logs, connection) }
            .onEach { dispatch(it) }
            .launchIn(scope)
    }

    private fun snapshot(
        boot: dev.shinglem.hermes.core.backend.BackendBootState,
        logs: List<dev.shinglem.hermes.core.backend.BackendLogLine>,
        connection: dev.shinglem.hermes.core.transport.ConnectionState,
    ): BackendResult = BackendResult.Snapshotted(
        kind = gateway.kind,
        boot = boot,
        connection = connection,
        logs = logs,
    )

    override fun executeIntent(intent: BackendIntent) {
        subscribeOnce()
        when (intent) {
            BackendIntent.Observe -> subscribeOnce()
            BackendIntent.Start -> runAction("启动后端") { gateway.start() }
            BackendIntent.Stop -> runAction("停止后端") { gateway.stop() }
            BackendIntent.Restart -> runAction("重启后端") {
                gateway.stop()
                gateway.start()
            }
            BackendIntent.Revalidate -> runAction("重新解析后端地址") { gateway.revalidate() }
            BackendIntent.Reconnect -> runAction("重连传输层") { gateway.reconnect() }
            BackendIntent.DismissNotice -> dispatch(BackendResult.Noticed(null))
            BackendIntent.CopyDiagnostics -> publish(BackendEffect.CopyToClipboard(diagnosticsText()))
        }
    }

    private fun runAction(label: String, action: suspend () -> Unit) {
        if (state().busy) return
        dispatch(BackendResult.Busy(true))
        dispatch(BackendResult.Noticed(null))
        scope.launch {
            try {
                action()
                dispatch(BackendResult.Noticed("$label：完成"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.warn("backend admin: $label 失败：${throwable.message}")
                dispatch(
                    BackendResult.Noticed(
                        "$label 失败：${throwable.message ?: throwable::class.simpleName}",
                    ),
                )
            } finally {
                dispatch(BackendResult.Busy(false))
            }
        }
    }

    /** 诊断文本：状态 + 端口 + 最近日志，**不含任何令牌**。 */
    private fun diagnosticsText(): String {
        val snapshot = state()
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
            appendLine("== 最近 ${BackendState.DIAGNOSTIC_LOG_LINES} 行后端日志 ==")
            val tail = snapshot.tail()
            if (tail.isEmpty()) {
                appendLine("（无输出）")
            } else {
                tail.forEach { appendLine("[${it.stream}] ${it.text}") }
            }
        }
    }
}