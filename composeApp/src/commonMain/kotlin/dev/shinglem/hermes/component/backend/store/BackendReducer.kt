package dev.shinglem.hermes.component.backend.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.backend.BackendResult
import dev.shinglem.hermes.component.backend.BackendState

/**
 * 后端管理页的**纯**状态迁移。
 *
 * 与 Updater 的区别：这一页的主体状态是**外部流的投影**，本地只有 `busy` / `notice`。
 * 所以 Reducer 里没有"业务分支"，只有赋值 —— 真正的逻辑在 `BackendExecutor` 的订阅里。
 */
internal class BackendReducer : Reducer<BackendState, BackendResult> {

    override fun BackendState.reduce(msg: BackendResult): BackendState = when (msg) {
        is BackendResult.Snapshotted -> copy(
            kind = msg.kind,
            boot = msg.boot,
            connection = msg.connection,
            logs = msg.logs,
        )

        is BackendResult.Busy -> copy(busy = msg.busy)

        is BackendResult.Noticed -> copy(notice = msg.message)
    }
}