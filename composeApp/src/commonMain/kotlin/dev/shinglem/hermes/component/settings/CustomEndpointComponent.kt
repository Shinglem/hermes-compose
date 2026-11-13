package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.settings.store.CustomEndpointStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** 自定义端点页（形态同 [SecurityComponent]，见 ADR-069）。 */
interface CustomEndpointComponent {
    val state: StateFlow<CustomEndpointState>
    val effects: Flow<CustomEndpointEffect>
    fun onIntent(intent: CustomEndpointIntent)

    fun onConnectedChanged(connected: Boolean) = onIntent(CustomEndpointIntent.Appear(connected))
}

class DefaultCustomEndpointComponent(
    private val componentContext: ComponentContext,
    gateway: CustomEndpointGateway,
    private val profile: String?,
    executorContext: CoroutineContext = Dispatchers.Default,
) : CustomEndpointComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder: StoreHolder<CustomEndpointState, CustomEndpointIntent, CustomEndpointEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = CustomEndpointStoreFactory.create(
                    gateway = gateway,
                    profile = profile,
                    executorContext = executorScope.coroutineContext,
                ),
                executorContext = executorScope.coroutineContext,
            )
        }

    override val state: StateFlow<CustomEndpointState> = holder.state

    override val effects: Flow<CustomEndpointEffect> = holder.labels

    override fun onIntent(intent: CustomEndpointIntent) = holder.dispatch(intent)

    override fun toString(): String = "CustomEndpointComponent(profile=$profile)"

    private companion object {
        const val STORE_KEY = "custom-endpoint"
    }
}
