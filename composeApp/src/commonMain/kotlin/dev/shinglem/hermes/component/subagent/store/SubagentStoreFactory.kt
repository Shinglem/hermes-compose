package dev.shinglem.hermes.component.subagent.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.subagent.SubagentGateway
import dev.shinglem.hermes.component.subagent.SubagentIntent
import dev.shinglem.hermes.component.subagent.SubagentResult
import dev.shinglem.hermes.component.subagent.SubagentState
import kotlin.coroutines.CoroutineContext

/** 造一个子代理面板的 Store。 */
internal object SubagentStoreFactory {

    fun create(
        gateway: SubagentGateway,
        executorContext: CoroutineContext,
        reducer: Reducer<SubagentState, SubagentResult> = SubagentReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<SubagentIntent, SubagentState, Nothing> =
        factory.create(
            initialState = SubagentState(),
            bootstrapper = null,
            executorFactory = { SubagentExecutor(gateway, executorContext) },
            reducer = reducer,
        )
}