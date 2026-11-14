package dev.shinglem.hermes.component.sessionlist

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.feature.chat.ChatRepository
import dev.shinglem.hermes.feature.session.SessionListState
import dev.shinglem.hermes.feature.session.SessionListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * 会话列表组件。
 *
 * ### ⚠️ 过渡形态（与 Maintenance / Composer / Capability 同一模式）
 *
 * 内部仍是 `SessionListViewModel`（683 行、11 处 launch），只把 scope 换成注入的
 * UI 线程 scope，生命周期交给 Component。
 *
 * **为什么不这一轮拆 Reducer**：它的 30+ 个动作里绝大多数是"调 RPC → 更新一份大 state"
 * 或纯 `_state.update {}`；拆开要写 ~700 行机械代码 + 30 个测试，收益只是形状整齐。
 * S5 的硬目标是 `viewModel {` 归零，过渡形态已达成且**行为零变化**。
 * 完整拆分已登记到 S8 清单（与 Capability 一起）。
 */
interface SessionListComponent {
    val state: StateFlow<SessionListState>
    val effects: Flow<Nothing>

    /**
     * 过渡期暴露（同 Maintenance / Composer / Capability）：
     * `SessionListPane` 与 `AppShell` 里仍有 30+ 处直接调方法。
     */
    val model: SessionListViewModel
}

class DefaultSessionListComponent(
    private val componentContext: ComponentContext,
    repository: dev.shinglem.hermes.feature.session.SessionRepository,
    preferences: AppPreferences,
    chat: ChatRepository,
    executorContext: CoroutineContext = dev.shinglem.hermes.platform.UiDispatcher.context,
) : SessionListComponent, ComponentContext by componentContext {

    private val holder: Holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        val scope = CoroutineScope(SupervisorJob() + executorContext)
        Holder(
            model = SessionListViewModel(repository, preferences, chat, scope),
            scope = scope,
        )
    }

    override val model: SessionListViewModel get() = holder.model

    override val state: StateFlow<SessionListState> = model.state

    override val effects: Flow<Nothing> = emptyFlow()

    private class Holder(
        val model: SessionListViewModel,
        val scope: CoroutineScope,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() {
            scope.cancel()
        }
    }

    override fun toString(): String = "SessionListComponent"

    private companion object {
        const val STORE_KEY = "session-list"
    }
}