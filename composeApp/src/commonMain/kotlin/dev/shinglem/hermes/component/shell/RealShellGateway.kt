package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.feature.settings.BrowserArgsReconciler
import dev.shinglem.hermes.feature.shell.ProfileRepository
import dev.shinglem.hermes.feature.shell.ProfileSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
}