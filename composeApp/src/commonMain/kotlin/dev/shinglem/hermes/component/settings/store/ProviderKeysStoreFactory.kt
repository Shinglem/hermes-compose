package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.settings.ProviderKeysEffect
import dev.shinglem.hermes.component.settings.ProviderKeysGateway
import dev.shinglem.hermes.component.settings.ProviderKeysIntent
import dev.shinglem.hermes.component.settings.ProviderKeysResult
import dev.shinglem.hermes.component.settings.ProviderKeysState
import kotlin.coroutines.CoroutineContext

/** 造一个提供方密钥页 Store（形状同 [SecurityStoreFactory]）。 */
internal object ProviderKeysStoreFactory {

    fun create(
        gateway: ProviderKeysGateway,
        profile: String?,
        executorContext: CoroutineContext,
        reducer: Reducer<ProviderKeysState, ProviderKeysResult> = ProviderKeysReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<ProviderKeysIntent, ProviderKeysState, ProviderKeysEffect> =
        factory.create(
            initialState = ProviderKeysState(),
            bootstrapper = null,
            executorFactory = { ProviderKeysExecutor(gateway, profile, executorContext) },
            reducer = reducer,
        )
}
