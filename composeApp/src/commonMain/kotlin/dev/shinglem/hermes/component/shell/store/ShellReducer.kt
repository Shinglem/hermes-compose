package dev.shinglem.hermes.component.shell.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.shell.ShellResult
import dev.shinglem.hermes.component.shell.ShellState
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull

/** Shell 全局状态的**纯**状态迁移。 */
internal class ShellReducer : Reducer<ShellState, ShellResult> {

    override fun ShellState.reduce(msg: ShellResult): ShellState = when (msg) {
        is ShellResult.ExternalSnapshot -> copy(
            connection = msg.connection,
            backendKind = msg.backendKind,
            backend = msg.backend,
            backendLogs = msg.backendLogs,
            gatewayProbe = msg.gatewayProbe,
            // health 失败时是 null：保留上一次成功的结果，别把版本号刷成空。
            health = msg.health ?: health,
        )

        // 已有列表时不退回 Loading：切 profile 时会闪一下空白（迁移前踩过）。
        ShellResult.ProfilesLoading -> if (profiles.valueOrNull == null) {
            copy(profiles = Loadable.Loading)
        } else {
            this
        }

        is ShellResult.ProfilesLoaded -> copy(
            profiles = Loadable.Ready(msg.profiles),
            // 当前档案可能已被删除：列表里找不到就回到"官方默认"。
            activeProfile = activeProfile?.takeIf { name ->
                msg.profiles.any { it.name == name }
            },
        )

        is ShellResult.ProfilesFailed -> copy(
            profiles = Loadable.Failed(msg.error, msg.stale),
        )

        is ShellResult.ProfileSelected -> copy(activeProfile = msg.name)

        is ShellResult.ModeChanged -> copy(mode = msg.mode)
    }
}