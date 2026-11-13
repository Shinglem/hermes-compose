package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.settings.CustomEndpointEffect
import dev.shinglem.hermes.component.settings.CustomEndpointGateway
import dev.shinglem.hermes.component.settings.CustomEndpointIntent
import dev.shinglem.hermes.component.settings.CustomEndpointResult
import dev.shinglem.hermes.component.settings.CustomEndpointState
import kotlin.coroutines.CoroutineContext

/** 造一个自定义端点页 Store（形状同前两个域）。 */
internal object CustomEndpointStoreFactory {

    fun create(
        gateway: CustomEndpointGateway,
        profile: String?,
        executorContext: CoroutineContext,
        reducer: Reducer<CustomEndpointState, CustomEndpointResult> = CustomEndpointReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<CustomEndpointIntent, CustomEndpointState, CustomEndpointEffect> =
        factory.create(
            initialState = CustomEndpointState(),
            bootstrapper = null,
            executorFactory = { CustomEndpointExecutor(gateway, profile, executorContext) },
            reducer = reducer,
        )
}
