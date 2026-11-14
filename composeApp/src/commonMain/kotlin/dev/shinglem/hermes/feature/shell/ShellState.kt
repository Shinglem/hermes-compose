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
import dev.shinglem.hermes.feature.settings.BrowserArgsReconciler
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
    /** 心跳：后端只有官方 gateway 一条链路，只探它。 */
    val gatewayProbe: ProbeState = ProbeState.Idle,
) {
    val profilesOrEmpty: List<ProfileSummary> get() = profiles.valueOrNull.orEmpty()

    val activeProfileKey: String get() = activeProfile ?: DEFAULT_PROFILE

    val activeProfileSummary: ProfileSummary?
        get() = profilesOrEmpty.firstOrNull { it.name == activeProfileKey }

    val activeProfileLabel: String
        get() = activeProfileSummary?.label ?: activeProfileKey

    val isConnected: Boolean get() = connection is ConnectionState.Connected
}



/** 官方默认档案名（S5：从 `ShellViewModel` 移到顶层常量，ViewModel 已删除）。 */
const val DEFAULT_PROFILE: String = "default"