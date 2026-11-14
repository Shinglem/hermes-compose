package dev.shinglem.hermes.component.composer

import androidx.compose.ui.text.input.TextFieldValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import dev.shinglem.hermes.feature.composer.ComposerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * 输入框组件。
 *
 * ### ⚠️ 过渡形态（与 S2 的 Maintenance 同一模式）
 *
 * 内部仍是 `ComposerViewModel`，只是把 scope 从 `viewModelScope` 换成 Component 的。
 * **没有**拆成 Contract/Reducer/Executor/Store，原因：
 *
 * 这一域的核心不是"状态迁移"，而是**补全的时序**：
 * - `scheduleCompletion` 是防抖的，且应用时刻要**重算区间**（用请求发出时的光标会错位，
 *   历史上真出过 `/skin` 变 `/skinill`）；
 * - 接受补全后不能立刻再请求同一条（自己匹配自己，浮层不消失）；
 * - `accept` 返回 Boolean 决定是否放行 Enter。
 *
 * 这三条都靠"协程 + 可变状态"配合正确，换成 Reducer 纯函数要先证明等价性 ——
 * 而 UI 侧的 30 多个消费点也会跟着变。这超出"移除 viewModel {"的目标，
 * 属于 S8 的单独一轮（已登记）。
 *
 * 现在这一步能达成的：`viewModel {` 归零 + 生命周期交给 Component（切走不销毁输入内容）。
 */
interface ComposerComponent {
    val state: StateFlow<ComposerState>
    val effects: Flow<ComposerEffect>
    fun onIntent(intent: ComposerIntent)

    /**
     * 过渡期暴露（与 Maintenance 同模式）：`Composer.kt` 里仍有 20+ 处直接调方法。
     * 逐条换 onIntent 是纯机械劳动，终态重写（S8）时一并换掉。新代码请走 [onIntent]。
     */
    val model: ComposerViewModel
}

class DefaultComposerComponent(
    private val componentContext: ComponentContext,
    repository: dev.shinglem.hermes.feature.composer.ComposerRepository,
    sessions: dev.shinglem.hermes.feature.composer.ComposerSessionContext,
    preferences: dev.shinglem.hermes.core.prefs.AppPreferences,
    executorContext: CoroutineContext = UiDispatcherForComposer.context,
) : ComposerComponent, ComponentContext by componentContext {

    private val holder: Holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        val scope = CoroutineScope(SupervisorJob() + executorContext)
        Holder(
            model = ComposerViewModel(repository, sessions, preferences, scope),
            scope = scope,
        )
    }

    override val model: ComposerViewModel get() = holder.model

    override val state: StateFlow<ComposerState> = model.state

    override val effects: Flow<ComposerEffect> = emptyFlow()

    override fun onIntent(intent: ComposerIntent) {
        when (intent) {
            is ComposerIntent.OpenSession -> model.openSession(intent.storedSessionId)
            is ComposerIntent.ValueChange -> model.onValueChange(intent.value)
            ComposerIntent.RequestCompletion -> model.requestCompletion()
            is ComposerIntent.MoveSelection -> model.moveSelection(intent.delta)
            is ComposerIntent.AcceptCompletion -> model.acceptCompletion(intent.index)
            ComposerIntent.DismissCompletion -> model.dismissCompletion()
            ComposerIntent.DismissNotice -> model.dismissNotice()
            is ComposerIntent.Quote -> model.quote(intent.text)
            is ComposerIntent.StepHistory -> model.stepHistory(intent.direction)
            is ComposerIntent.MarkSent -> model.markSent(intent.text)
        }
    }

    private class Holder(
        val model: ComposerViewModel,
        val scope: CoroutineScope,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() {
            scope.cancel()
        }
    }

    override fun toString(): String = "ComposerComponent"

    private companion object {
        const val STORE_KEY = "composer"
    }
}

/**
 * 输入框的 dispatcher。
 *
 * 与 Chat 同理：`ComposerViewModel` 持有非线程安全状态（`completionJob` / `draftJob` /
 * 历史游标），靠单线程串行化。这里复用 `UiDispatcher`，不要改成 `Default`。
 */
internal object UiDispatcherForComposer {
    val context: CoroutineContext get() = dev.shinglem.hermes.platform.UiDispatcher.context
}
