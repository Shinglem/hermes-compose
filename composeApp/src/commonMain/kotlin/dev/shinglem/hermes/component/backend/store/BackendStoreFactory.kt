package dev.shinglem.hermes.component.backend.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.backend.BackendEffect
import dev.shinglem.hermes.component.backend.BackendGateway
import dev.shinglem.hermes.component.backend.BackendIntent
import dev.shinglem.hermes.component.backend.BackendResult
import dev.shinglem.hermes.component.backend.BackendState
import kotlin.coroutines.CoroutineContext

/** 造一个后端管理页的 Store。 */
internal object BackendStoreFactory {

    fun create(
        gateway: BackendGateway,
        executorContext: CoroutineContext,
        reducer: Reducer<BackendState, BackendResult> = BackendReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<BackendIntent, BackendState, BackendEffect> =
        factory.create(
            initialState = BackendState(kind = gateway.kind),
            bootstrapper = null,
            executorFactory = { BackendExecutor(gateway, executorContext) },
            reducer = reducer,
        )
}