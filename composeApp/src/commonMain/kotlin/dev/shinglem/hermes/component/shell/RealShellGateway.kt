package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.transport.BackendHealth
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.feature.settings.BrowserArgsReconciler
import dev.shinglem.hermes.feature.shell.ProfileRepository
import dev.shinglem.hermes.feature.shell.ProfileSummary
import dev.shinglem.hermes.feature.shell.toPreference
import dev.shinglem.hermes.platform.UiDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [ShellGateway] 的真实实现。
 *
 * 五条数据全部转发已有对象，**不加缓存** —— 它们本身已经是 `StateFlow`。
 * 唯一的本地状态是 `gatewayProbe`（心跳探活结果），因为迁移前它就是
 * `ShellViewModel` 自己维护的（`restClient.probeStatus` 不是流）。
 */
class RealShellGateway(
    private val gateway: GatewayClient,
    private val profileRepository: ProfileRepository,
    private val restClient: RestClient,
    private val preferences: AppPreferences,
    private val backend: BackendProvider,
    /** 管理员运行时非 null；非提权时为 null（对账什么都不做）。 */
    private val browserArgsReconciler: BrowserArgsReconciler? = null,
) : ShellGateway {

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    override val gatewayProbe: StateFlow<ProbeState> = _probe.asStateFlow()

    private val _health = MutableStateFlow<BackendHealth?>(null)
    override val health: StateFlow<BackendHealth?> = _health.asStateFlow()

    /**
     * 心跳与 health 的轮询在这里起（**不在 Executor 里**）。
     *
     * 放 Executor 里会让 `runTest` 的 `advanceUntilIdle()` 永远不 idle（`while(true)` +
     * `delay`），测试直接挂住。Gateway 是"实现方"，它自己管自己的后台循环更合适 ——
     * Executor 只负责合并流。
     *
     * 循环挂在 `backend` 的生命周期上：`DesktopContainer` 在退出时 `close()` 掉后端，
     * 这里随之停止。作用域用 `CoroutineScope(SupervisorJob() + UiDispatcher.context)`
     * 之外的单线程上下文会破坏"状态线程 = UI 线程"的约定，所以直接用 UI 线程。
     */
    private val pollingScope = CoroutineScope(SupervisorJob() + UiDispatcher.context)

    init {
        pollingScope.launch {
            while (isActive) {
                probeOnce()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
        pollingScope.launch {
            while (isActive) {
                healthOnce()
                delay(HEALTH_INTERVAL_MS)
            }
        }
    }

    /** 探一次后端（探 HTTP 而不是只看 WS：WS 半死时 socket 可能还在，而 HTTP 已不通）。 */
    private suspend fun probeOnce() {
        // 本地后端还没就绪时探活只会刷失败：跳过，等 Ready。
        val boot = backend.state.value
        if (boot !is BackendBootState.Ready && backend.kind == BackendKind.LOCAL) return
        _probe.value = try {
            ProbeState.Ok(restClient.probeStatus(restClient.currentBaseUrl()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            ProbeState.Fail(throwable.message?.takeIf { it.isNotBlank() } ?: "无响应")
        }
    }

    /** health 只是状态栏/关于页的附加信息，失败保留上一次的值（不清空）。 */
    private suspend fun healthOnce() {
        runCatching { restClient.health() }
            .onSuccess { _health.value = it }
            .onFailure { Log.debug("health probe failed: ${it.message}") }
    }

    override val connection: StateFlow<ConnectionState> get() = gateway.connection
    override val backendKind: BackendKind get() = backend.kind
    override val backendState: StateFlow<BackendBootState> get() = backend.state
    override val backendLogs: StateFlow<List<BackendLogLine>> get() = backend.logs

    override suspend fun loadProfiles(): List<ProfileSummary> = profileRepository.list()

    override fun persistActiveProfile(name: String?) {
        preferences.activeProfile = name
        // 点档案 = 去这个档案的会话（ADR-044）。
        preferences.mode = AppPreferences.MODE_SESSIONS
    }

    override fun persistMode(mode: ShellMode) {
        preferences.mode = mode.toPreference()
    }

    override suspend fun startBackend() {
        backend.start()
    }

    override fun reconnect() {
        gateway.stop()
        gateway.start()
    }

    override suspend fun reconcileBrowserArgsOnce() {
        // 非提权时 reconciler 为 null：不去动用户的配置。
        browserArgsReconciler?.reconcile()
    }

    /** 停止后台轮询（容器 `close()` 时调用；对象本身会随容器一起丢弃）。 */
    fun dispose() {
        pollingScope.cancel()
    }

    private companion object {
        /** 心跳间隔：5s 足够看出"后端死了"，又不会把日志/CPU 刷起来（迁移前同值）。 */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** `/api/health` 只是版本号之类的附加信息，30s 一次足够。 */
        const val HEALTH_INTERVAL_MS = 30_000L
    }
}