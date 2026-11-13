package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.settings.store.MoaStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** MoA 页（形态同 [SecurityComponent]，见 ADR-069）。 */
interface MoaComponent {
    val state: StateFlow<MoaState>
    val effects: Flow<MoaEffect>
    fun onIntent(intent: MoaIntent)

    fun onConnectedChanged(connected: Boolean) = onIntent(MoaIntent.Appear(connected))
}

class DefaultMoaComponent(
    private val componentContext: ComponentContext,
    gateway: MoaGateway,
    private val profile: String?,
    executorContext: CoroutineContext = Dispatchers.Default,
) : MoaComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder: StoreHolder<MoaState, MoaIntent, MoaEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = MoaStoreFactory.create(
                    gateway = gateway,
                    profile = profile,
                    executorContext = executorScope.coroutineContext,
                ),
                executorContext = executorScope.coroutineContext,
            )
        }

    override val state: StateFlow<MoaState> = holder.state

    override val effects: Flow<MoaEffect> = holder.labels

    override fun onIntent(intent: MoaIntent) = holder.dispatch(intent)

    override fun toString(): String = "MoaComponent(profile=$profile)"

    private companion object {
        const val STORE_KEY = "moa"
    }
}
