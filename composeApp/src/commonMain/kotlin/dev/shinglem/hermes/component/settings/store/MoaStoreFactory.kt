package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.settings.MoaEffect
import dev.shinglem.hermes.component.settings.MoaGateway
import dev.shinglem.hermes.component.settings.MoaIntent
import dev.shinglem.hermes.component.settings.MoaResult
import dev.shinglem.hermes.component.settings.MoaState
import kotlin.coroutines.CoroutineContext

/** 造一个 MoA 页 Store（形状同前几个域）。 */
internal object MoaStoreFactory {

    fun create(
        gateway: MoaGateway,
        profile: String?,
        executorContext: CoroutineContext,
        reducer: Reducer<MoaState, MoaResult> = MoaReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<MoaIntent, MoaState, MoaEffect> =
        factory.create(
            initialState = MoaState(),
            bootstrapper = null,
            executorFactory = { MoaExecutor(gateway, profile, executorContext) },
            reducer = reducer,
        )
}
