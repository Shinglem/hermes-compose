package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.settings.store.ProfilesStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** 配置档案页（形态同 [SecurityComponent]，见 ADR-069）。 */
interface ProfilesComponent {
    val state: StateFlow<ProfilesState>
    val effects: Flow<ProfilesEffect>
    fun onIntent(intent: ProfilesIntent)

    fun onConnectedChanged(connected: Boolean) = onIntent(ProfilesIntent.Appear(connected))
}

class DefaultProfilesComponent(
    private val componentContext: ComponentContext,
    gateway: ProfilesGateway,
    executorContext: CoroutineContext = Dispatchers.Default,
) : ProfilesComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder: StoreHolder<ProfilesState, ProfilesIntent, ProfilesEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = ProfilesStoreFactory.create(
                    gateway = gateway,
                    executorContext = executorScope.coroutineContext,
                ),
                executorContext = executorScope.coroutineContext,
            )
        }

    override val state: StateFlow<ProfilesState> = holder.state

    override val effects: Flow<ProfilesEffect> = holder.labels

    override fun onIntent(intent: ProfilesIntent) = holder.dispatch(intent)

    private companion object {
        const val STORE_KEY = "profiles"
    }
}
