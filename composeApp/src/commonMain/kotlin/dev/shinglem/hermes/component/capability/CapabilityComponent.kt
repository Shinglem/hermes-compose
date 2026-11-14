package dev.shinglem.hermes.component.capability

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.feature.capabilities.CapabilityRepository
import dev.shinglem.hermes.feature.capabilities.CapabilityState
import dev.shinglem.hermes.feature.capabilities.CapabilityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * 能力页组件。
 *
 * ### ⚠️ 过渡形态（与 S2 的 Maintenance / S5 的 Composer 同一模式）
 *
 * 内部仍是 `CapabilityViewModel`（583 行、20+ 个动作），只把 scope 从 `viewModelScope`
 * 换成注入的 UI 线程 scope，生命周期交给 Component。
 *
 * **为什么这一轮不拆 Reducer**：它的动作绝大多数是"调 REST → 更新一份大 state"，
 * 拆开要写 ~600 行机械代码 + 20 个测试，收益只是"形状更整齐"。而 S5 的硬目标是
 * `viewModel {` 归零 —— 过渡形态已经达成，且**行为零变化**（风险最低）。
 * 完整拆分已登记到 S8 清单。
 */
interface CapabilityComponent {
    val state: StateFlow<CapabilityState>
    val effects: Flow<Nothing>

    /**
     * 过渡期暴露（与 Maintenance / Composer 同模式）：`CapabilityPage` 里仍有 20+ 处
     * 直接调方法。新代码请优先用 Component 的方法。
     */
    val model: CapabilityViewModel
}

class DefaultCapabilityComponent(
    private val componentContext: ComponentContext,
    repository: CapabilityRepository,
    executorContext: CoroutineContext = dev.shinglem.hermes.platform.UiDispatcher.context,
) : CapabilityComponent, ComponentContext by componentContext {

    private val holder: Holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        val scope = CoroutineScope(SupervisorJob() + executorContext)
        Holder(
            model = CapabilityViewModel(repository, scope),
            scope = scope,
        )
    }

    override val model: CapabilityViewModel get() = holder.model

    override val state: StateFlow<CapabilityState> = model.state

    override val effects: Flow<Nothing> = emptyFlow()

    private class Holder(
        val model: CapabilityViewModel,
        val scope: CoroutineScope,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() {
            // 能力页没有草稿要 flush，直接取消。
            scope.cancel()
        }
    }

    override fun toString(): String = "CapabilityComponent"

    private companion object {
        const val STORE_KEY = "capability"
    }
}