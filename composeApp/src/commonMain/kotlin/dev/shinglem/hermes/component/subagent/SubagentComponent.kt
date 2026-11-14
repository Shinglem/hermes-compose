package dev.shinglem.hermes.component.subagent

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.subagent.store.SubagentStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/** 子代理面板（S5：从 `SubagentViewModel` 迁来）。 */
interface SubagentComponent {
    val state: StateFlow<SubagentState>
    val effects: Flow<Nothing>
    fun onIntent(intent: SubagentIntent)
}

class DefaultSubagentComponent(
    private val componentContext: ComponentContext,
    gateway: SubagentGateway,
    executorContext: CoroutineContext = Dispatchers.Default,
) : SubagentComponent, ComponentContext by componentContext {

    private val holder: StoreHolder<SubagentState, SubagentIntent, Nothing> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = SubagentStoreFactory.create(gateway, executorContext),
                executorContext = executorContext,
            )
        }

    override val state: StateFlow<SubagentState> = holder.state

    override val effects: Flow<Nothing> = emptyFlow()

    init {
        holder.dispatch(SubagentIntent.Observe)
    }

    override fun onIntent(intent: SubagentIntent) = holder.dispatch(intent)

    override fun toString(): String = "SubagentComponent"

    private companion object {
        const val STORE_KEY = "subagent"
    }
}