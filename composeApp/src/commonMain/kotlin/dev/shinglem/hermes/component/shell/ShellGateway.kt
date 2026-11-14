package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.feature.shell.ProfileSummary
import dev.shinglem.hermes.core.transport.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Shell 的数据入口（S5 模板）。
 *
 * 抽成接口的目的是让 `ShellExecutor` 能用假实现测试 —— 这一页有几条**时序敏感**的分支
 * （后端就绪后主动重连、profile 被删后回落），用真后端验很难造出那些窗口。
 */
interface ShellGateway {
    val connection: StateFlow<ConnectionState>
    val backendKind: BackendKind
    val backendState: StateFlow<BackendBootState>
    val backendLogs: StateFlow<List<BackendLogLine>>
    val gatewayProbe: StateFlow<ProbeState>

    /** `GET /api/profiles`。 */
    suspend fun loadProfiles(): List<ProfileSummary>

    /** 切换档案时持久化（迁移前由 `preferences` 做）。 */
    fun persistActiveProfile(name: String?)

    /** 启动/重试拉起本地后端（幂等）。 */
    suspend fun startBackend()

    /** 重连传输层：`gateway.stop()` + `gateway.start()`。 */
    fun reconnect()

    /**
     * 管理员模式下一次性的浏览器参数对账。
     *
     * 非提权时实现里应当什么都不做（迁移前传 null 的语义）。
     */
    suspend fun reconcileBrowserArgsOnce(): Unit
}