package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import dev.shinglem.hermes.component.settings.ProfilesEffect
import dev.shinglem.hermes.component.settings.ProfilesGateway
import dev.shinglem.hermes.component.settings.ProfilesIntent
import dev.shinglem.hermes.component.settings.ProfilesResult
import dev.shinglem.hermes.component.settings.ProfilesState
import kotlin.coroutines.CoroutineContext

/** 造一个配置档案页 Store。 */
internal object ProfilesStoreFactory {

    fun create(
        gateway: ProfilesGateway,
        executorContext: CoroutineContext,
        reducer: Reducer<ProfilesState, ProfilesResult> = ProfilesReducer(),
        factory: StoreFactory = DefaultStoreFactory(),
    ): Store<ProfilesIntent, ProfilesState, ProfilesEffect> =
        factory.create(
            initialState = ProfilesState(),
            bootstrapper = null,
            executorFactory = { ProfilesExecutor(gateway, executorContext) },
            reducer = reducer,
        )
}
