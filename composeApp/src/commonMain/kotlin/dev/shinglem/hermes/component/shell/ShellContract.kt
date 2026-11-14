package dev.shinglem.hermes.component.shell

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.feature.shell.ProfileSummary
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.core.transport.BackendHealth
import dev.shinglem.hermes.core.transport.ConnectionState


/**
 * Shell 的全局状态（S5：从 `ShellState` 迁来）。
 *
 * 这是**全局**的那一份（profile 列表、连接状态、后端状态），不是某一页的。
 * 迁移前它由 `ShellViewModel` 用 5 条流的 `combine` 合成，现在改成 Store。
 */
@Immutable
data class ShellState(
    val mode: ShellMode = ShellMode.SESSIONS,
    val profiles: Loadable<List<ProfileSummary>> = Loadable.Idle,
    val activeProfile: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val backendKind: BackendKind = BackendKind.REMOTE,
    val backend: BackendBootState = BackendBootState.Idle,
    val backendLogs: List<BackendLogLine> = emptyList(),
    val gatewayProbe: ProbeState = ProbeState.Idle,
    /** 后端健康信息（版本号等）。迁移前在旧 ShellState 上，UI 有两处消费。 */
    val health: BackendHealth? = null,
) {
    val profilesOrEmpty: List<ProfileSummary> get() = profiles.valueOrNull.orEmpty()

    /** 空 profile 表示"官方默认"（`default`），与迁移前的写法一致。 */
    val activeProfileKey: String get() = activeProfile ?: DEFAULT_PROFILE

    val activeProfileSummary: ProfileSummary?
        get() = activeProfile?.let { name -> profilesOrEmpty.firstOrNull { it.name == name } }

    val activeProfileLabel: String
        get() = activeProfileSummary?.displayName?.takeIf { it.isNotBlank() }
            ?: activeProfileKey

    val isConnected: Boolean get() = connection is ConnectionState.Connected

    companion object {
        const val DEFAULT_PROFILE = "default"
    }
}

sealed interface ShellIntent {
    /** 开始订阅外部流（幂等）。Component 创建时自发一次。 */
    data object Observe : ShellIntent

    /** 刷新 profile 列表。 */
    data object RefreshProfiles : ShellIntent

    /**
     * 切换当前档案。
     *
     * `null` = 官方默认。迁移前这项工作由 `selectProfile` 做（含持久化）。
     */
    data class SelectProfile(val name: String?) : ShellIntent

    /** 切到能力页。能力入口是**导航目的地**：从会话/设置点它都落到能力页。 */
    data object OpenCapabilities : ShellIntent

    /** 切到设置页。**不写偏好** —— 它是一次性去处，重启应回到会话。 */
    data object OpenSettings : ShellIntent

    data object RetryBackend : ShellIntent
    data object RetryConnection : ShellIntent
}

/** 左侧头像条上选中的模式（沿用旧枚举，避免这一轮改 UI；S8 再合并）。 */
typealias ShellMode = dev.shinglem.hermes.feature.shell.ShellMode