package dev.shinglem.hermes.component.backend

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.transport.ConnectionState

/**
 * Reducer 的输入。
 *
 * 两类来源：
 * - **外部流**（`Snapshotted`）：`BackendProvider.state` / `logs` 与 `GatewayClient.connection`
 *   由 Executor 订阅后丢进来。迁移前这一步是 `combine(...).stateIn(...)`。
 * - **本地动作**（`Busy` / `Noticed`）：用户点了什么、结果如何。
 */
sealed interface BackendResult {
    /**
     * 三个外部流的合并快照。
     *
     * 分开传而不是传一个 `BackendState`：Reducer 需要知道"哪一条变了"才能决定
     * 要不要清 notice（例如后端刚启动成功时，上一条"启动失败"就不该继续显示）。
     */
    data class Snapshotted(
        val kind: BackendKind?,
        val boot: BackendBootState,
        val connection: ConnectionState,
        val logs: List<BackendLogLine>,
    ) : BackendResult

    data class Busy(val busy: Boolean) : BackendResult

    data class Noticed(val message: String?) : BackendResult
}