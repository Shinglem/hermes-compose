package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.core.transport.BackendHealth
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ShellMode { SESSIONS, CAPABILITIES, SETTINGS }

data class ShellState(
    val mode: ShellMode = ShellMode.SESSIONS,
    val profiles: Loadable<List<ProfileSummary>> = Loadable.Idle,
    /** null 表示 `default` profile。 */
    val activeProfile: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val health: BackendHealth? = null,
    /** 后端来源与启动状态：状态栏展示进度，失败时点击看详情。 */
    val backendKind: BackendKind = BackendKind.REMOTE,
    val backend: BackendBootState = BackendBootState.Idle,
    val backendLogs: List<BackendLogLine> = emptyList(),
    /** 心跳：gateway（JSON-RPC/REST）与 dashboard（Web UI）分别探测。 */
    val gatewayProbe: ProbeState = ProbeState.Idle,
    val dashboardProbe: ProbeState = ProbeState.Idle,
) {
    val profilesOrEmpty: List<ProfileSummary> get() = profiles.valueOrNull.orEmpty()

    val activeProfileKey: String get() = activeProfile ?: ShellViewModel.DEFAULT_PROFILE

    val activeProfileSummary: ProfileSummary?
        get() = profilesOrEmpty.firstOrNull { it.name == activeProfileKey }

    val activeProfileLabel: String
        get() = activeProfileSummary?.label ?: activeProfileKey

    val isConnected: Boolean get() = connection is ConnectionState.Connected
}

class ShellViewModel(
    private val gateway: GatewayClient,
    private val profileRepository: ProfileRepository,
    private val restClient: RestClient,
    private val preferences: AppPreferences,
    private val backend: BackendProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ShellState(
            mode = preferences.mode.toShellMode(),
            activeProfile = preferences.activeProfile,
            backendKind = backend.kind,
            backend = backend.state.value,
            backendLogs = backend.logs.value,
        ),
    )
    val state: StateFlow<ShellState> = _state.asStateFlow()

    private var profilesLoaded = false
    private var gatewayRestartedForBackend = false

    init {
        gateway.start()
        viewModelScope.launch {
            gateway.connection.collect { connection ->
                _state.update { it.copy(connection = connection) }
                if (connection is ConnectionState.Connected) {
                    if (!profilesLoaded) loadProfiles()
                    refreshHealth()
                }
            }
        }
        // 后端启动/日志：状态栏实时显示，失败时详情对话框里有原因与控制台尾。
        viewModelScope.launch {
            backend.state.collect { boot ->
                _state.update { it.copy(backend = boot) }
                // 本地后端就绪前 client 连的是一个必然失败的占位地址；
                // 就绪后主动重连一次，别让用户等指数退避的下一个窗口。
                if (boot is BackendBootState.Ready && !gatewayRestartedForBackend) {
                    gatewayRestartedForBackend = true
                    gateway.stop()
                    gateway.start()
                }
            }
        }
        viewModelScope.launch {
            backend.logs.collect { lines -> _state.update { it.copy(backendLogs = lines) } }
        }
        startHeartbeat()
    }

    /**
     * 心跳：同端口的两个能力分开探 —— `网关` 探 `/api/status`（JSON-RPC / REST），
     * `服务` 探 `/`（Web UI）。两者可以一好一坏：payload 缺 `web_dist` 时正是这种形态。
     *
     * 探 HTTP 而不是只看 WS 连接 —— WS 半死时 socket 可能还在，而 HTTP 已经不通。
     */
    private fun startHeartbeat() {
        viewModelScope.launch {
            while (isActive) {
                probeBackends()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun probeBackends() {
        val boot = backend.state.value
        // 本地后端还没就绪时探活只会刷失败：跳过，等 Ready。
        if (boot !is BackendBootState.Ready && backend.kind == BackendKind.LOCAL) return

        val gatewayBase = backend.currentTarget().baseUrl
        val gatewayProbe = probe(gatewayBase)
        // 服务（Web UI）地址：Local 由后端报告；Remote 没有报告，但 dashboard 的 Web UI
        // 与网关**同端口**（9119），所以直接探同一个 base —— 否则 remote 模式永远看不到「服务」。
        val serviceUrl = (boot as? BackendBootState.Ready)?.dashboardUrl
            ?: gatewayBase.takeIf { backend.kind == BackendKind.REMOTE }
        val dashboardProbe = if (serviceUrl.isNullOrBlank()) {
            ProbeState.Idle
        } else {
            probeService(serviceUrl)
        }

        _state.update { it.copy(gatewayProbe = gatewayProbe, dashboardProbe = dashboardProbe) }
    }

    private suspend fun probe(baseUrl: String): ProbeState = try {
        ProbeState.Ok(restClient.probeStatus(baseUrl))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        ProbeState.Fail(throwable.message?.takeIf { it.isNotBlank() } ?: "无响应")
    }

    private suspend fun probeService(baseUrl: String): ProbeState = try {
        ProbeState.Ok(restClient.probeWeb(baseUrl))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        ProbeState.Fail(throwable.message?.takeIf { it.isNotBlank() } ?: "无响应")
    }

    /** 重试拉起本地后端（幂等：已就绪时立即返回）。 */
    fun retryBackend() {
        viewModelScope.launch { backend.start() }
    }

    fun selectProfile(name: String?) {
        val normalized = name?.takeIf { it.isNotBlank() && it != DEFAULT_PROFILE }
        preferences.activeProfile = normalized
        // 点档案 = 去这个档案的会话（ADR-044）：在设置/能力页点 profile 也要有反应。
        val mode = ShellMode.SESSIONS
        preferences.mode = mode.toPreference()
        _state.update { it.copy(activeProfile = normalized, mode = mode) }
    }

    /** 能力入口是导航目的地：从会话/设置点它都落到能力页；回会话靠点 profile 头像。 */
    fun openCapabilities() {
        preferences.mode = ShellMode.CAPABILITIES.toPreference()
        _state.update { it.copy(mode = ShellMode.CAPABILITIES) }
    }

    /** 设置页不写偏好：它是一次性去处，重启应回到会话。 */
    fun openSettings() {
        _state.update { it.copy(mode = ShellMode.SETTINGS) }
    }

    fun retryConnection() {
        gateway.stop()
        gateway.start()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            _state.update { it.copy(profiles = Loadable.Loading) }
            try {
                val profiles = profileRepository.list()
                profilesLoaded = true
                // `state.json` 记的是「上次用的 profile」，它属于当时的后端。切到 local 之后
                // 那个名字（典型：remote 安装里的 butler）在新 home 里根本不存在 ——
                // 继续沿用它会让会话/技能全空。不存在就回落到 `default`，并写回偏好。
                val current = _state.value.activeProfile
                val names = profiles.map { it.name }
                if (current != null && current !in names) {
                    Log.info("profile '$current' 在当前后端不存在，回落到 ${DEFAULT_PROFILE}")
                    preferences.activeProfile = null
                    _state.update { it.copy(activeProfile = null, profiles = Loadable.Ready(profiles)) }
                } else {
                    _state.update { it.copy(profiles = Loadable.Ready(profiles)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = ErrorMapper.from(throwable, "profiles.list")
                Log.warn("profiles.list failed: ${error}", throwable)
                _state.update { current ->
                    current.copy(profiles = Loadable.Failed(error, current.profiles.valueOrNull))
                }
            }
        }
    }

    private fun refreshHealth() {
        viewModelScope.launch {
            try {
                val health = restClient.health()
                _state.update { it.copy(health = health) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                // 健康信息只是状态条的附加内容，失败不打断主流程。
                Log.debug("health probe failed: ${throwable.message}")
            }
        }
    }

    override fun onCleared() {
        gateway.stop()
    }

    companion object {
        const val DEFAULT_PROFILE = "default"

        /** 心跳间隔：5s 足够看出「后端死了」，又不会把日志/CPU 刷起来。 */
        const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}

private fun String.toShellMode(): ShellMode =
    if (this == AppPreferences.MODE_CAPABILITIES) ShellMode.CAPABILITIES else ShellMode.SESSIONS

private fun ShellMode.toPreference(): String =
    if (this == ShellMode.CAPABILITIES) AppPreferences.MODE_CAPABILITIES else AppPreferences.MODE_SESSIONS
