package dev.shinglem.hermes.component.shell.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.shell.ShellGateway
import dev.shinglem.hermes.component.shell.ShellIntent
import dev.shinglem.hermes.component.shell.ShellMode
import dev.shinglem.hermes.component.shell.ShellResult
import dev.shinglem.hermes.component.shell.ShellState
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Shell 的执行器。
 *
 * 迁移前 `ShellViewModel` 里有四条 `viewModelScope.launch` 分别收集
 * connection / backend.state / backend.logs 与心跳；这里合并成**一条** `combine`
 * 订阅 —— 少三条协程，也少三个"只更新一半状态"的中间态。
 */
internal class ShellExecutor(
    private val gateway: ShellGateway,
    executorContext: CoroutineContext,
) : CoroutineExecutor<ShellIntent, Nothing, ShellState, ShellResult, Nothing>(executorContext) {

    private var subscribed = false
    private var profilesLoaded = false
    private var gatewayRestartedForBackend = false
    private var browserArgsReconciled = false

    /**
     * 懒订阅：MVIKotlin 的 Executor 在构造期不能 dispatch
     * （`Value was not initialized`），所以由 `Observe` intent 触发。
     */
    private fun subscribeOnce() {
        if (subscribed) return
        subscribed = true
        combine(
            gateway.connection,
            gateway.backendState,
            gateway.backendLogs,
            gateway.gatewayProbe,
        ) { connection, backend, logs, probe -> Quad(connection, backend, logs, probe) }
            .onEach { snapshot ->
                dispatch(
                    ShellResult.ExternalSnapshot(
                        connection = snapshot.connection,
                        backendKind = gateway.backendKind,
                        backend = snapshot.backend,
                        backendLogs = snapshot.logs,
                        gatewayProbe = snapshot.probe,
                    ),
                )
                handleSideEffects(snapshot.connection, snapshot.backend)
            }
            .launchIn(scope)
    }

    private data class Quad(
        val connection: dev.shinglem.hermes.core.transport.ConnectionState,
        val backend: BackendBootState,
        val logs: List<dev.shinglem.hermes.core.backend.BackendLogLine>,
        val probe: dev.shinglem.hermes.core.backend.ProbeState,
    )

    /**
     * 两件"流变化时才该做"的事（迁移前也是这么挂的）：
     *
     * 1. 连上后第一次拉 profile 列表 + 一次性的浏览器参数对账；
     * 2. 本地后端就绪后**主动重连一次** —— 就绪前 client 连的是必然失败的占位地址，
     *    不主动重连就得等指数退避的下一个窗口（用户感知为"卡几秒"）。
     */
    private fun handleSideEffects(
        connection: dev.shinglem.hermes.core.transport.ConnectionState,
        backend: BackendBootState,
    ) {
        if (connection is dev.shinglem.hermes.core.transport.ConnectionState.Connected) {
            if (!profilesLoaded) {
                profilesLoaded = true
                reloadProfiles()
            }
            reconcileBrowserArgsOnce()
        }
        if (backend is BackendBootState.Ready && !gatewayRestartedForBackend) {
            gatewayRestartedForBackend = true
            gateway.reconnect()
        }
    }

    override fun executeIntent(intent: ShellIntent) {
        when (intent) {
            ShellIntent.Observe -> subscribeOnce()
            ShellIntent.RefreshProfiles -> reloadProfiles()
            is ShellIntent.SelectProfile -> {
                val normalized = intent.name?.takeIf { it.isNotBlank() && it != ShellState.DEFAULT_PROFILE }
                gateway.persistActiveProfile(normalized)
                dispatch(ShellResult.ProfileSelected(normalized))
                // 点档案 = 去这个档案的会话（ADR-044）：在设置/能力页点 profile 也要有反应。
                dispatch(ShellResult.ModeChanged(ShellMode.SESSIONS))
            }
            ShellIntent.OpenCapabilities -> dispatch(ShellResult.ModeChanged(ShellMode.CAPABILITIES))
            ShellIntent.OpenSettings -> dispatch(ShellResult.ModeChanged(ShellMode.SETTINGS))
            ShellIntent.RetryBackend -> scope.launch {
                try {
                    gateway.startBackend()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    Log.warn("retryBackend failed: ${throwable.message}")
                }
            }
            ShellIntent.RetryConnection -> gateway.reconnect()
        }
    }

    private fun reloadProfiles() {
        dispatch(ShellResult.ProfilesLoading)
        scope.launch {
            try {
                val profiles = gateway.loadProfiles()
                // profile 被删时回落：`state.json` 记的是"上次用的 profile"，切后端之后
                // 那个名字可能根本不存在 —— 继续沿用会让会话/技能全空。
                val current = state().activeProfile
                if (current != null && profiles.none { it.name == current }) {
                    Log.info("profile '$current' 在当前后端不存在，回落到 ${ShellState.DEFAULT_PROFILE}")
                    gateway.persistActiveProfile(null)
                }
                dispatch(ShellResult.ProfilesLoaded(profiles))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(
                    ShellResult.ProfilesFailed(
                        throwable.asAppError("profiles.list"),
                        state().profiles.valueOrNull,
                    ),
                )
            }
        }
    }

    /**
     * 管理员模式的一次性对账（迁移前 `reconcileBrowserArgsOnce`）。
     *
     * 只做一次：它改的是持久配置，重连（端口漂移、心跳恢复）不该反复写盘。
     */
    private fun reconcileBrowserArgsOnce() {
        if (browserArgsReconciled) return
        browserArgsReconciled = true
        scope.launch {
            runCatching { gateway.reconcileBrowserArgsOnce() }
                .onFailure { Log.warn("浏览器参数对账异常：${it.message}") }
        }
    }
}