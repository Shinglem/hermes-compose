package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.feature.shell.ProfileSummary
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.transport.ConnectionState

/** Reducer 的输入。外部流快照 + 本地动作的结果。 */
sealed interface ShellResult {
    /** 外部流合并快照（连接 / 后端 / 探针）。 */
    data class ExternalSnapshot(
        val connection: ConnectionState,
        val backendKind: BackendKind,
        val backend: BackendBootState,
        val backendLogs: List<BackendLogLine>,
        val gatewayProbe: ProbeState,
    ) : ShellResult

    data object ProfilesLoading : ShellResult

    data class ProfilesLoaded(val profiles: List<ProfileSummary>) : ShellResult

    /** 加载失败带上旧值：后端连不上时用户仍应看到上一次的列表。 */
    data class ProfilesFailed(val error: AppError, val stale: List<ProfileSummary>?) : ShellResult

    data class ProfileSelected(val name: String?) : ShellResult

    data class ModeChanged(val mode: ShellMode) : ShellResult
}