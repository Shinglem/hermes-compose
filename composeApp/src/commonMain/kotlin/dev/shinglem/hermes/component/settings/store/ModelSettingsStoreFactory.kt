package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.settings.ModelSettingsEffect
import dev.shinglem.hermes.component.settings.ModelSettingsGateway
import dev.shinglem.hermes.component.settings.ModelSettingsIntent
import dev.shinglem.hermes.component.settings.ModelSettingsResult
import dev.shinglem.hermes.component.settings.ModelSettingsState
import kotlin.coroutines.CoroutineContext

/** 造一个模型配置页 Store（形状同前几个域）。 */
internal object ModelSettingsStoreFactory {

    fun create(
        gateway: ModelSettingsGateway,
        profile: String?,
        executorContext: CoroutineContext,
        reducer: Reducer<ModelSettingsState, ModelSettingsResult> = ModelSettingsReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<ModelSettingsIntent, ModelSettingsState, ModelSettingsEffect> =
        factory.create(
            initialState = ModelSettingsState(),
            bootstrapper = null,
            executorFactory = { ModelSettingsExecutor(gateway, profile, executorContext) },
            reducer = reducer,
        )
}
