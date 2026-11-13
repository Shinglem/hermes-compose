package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.settings.store.ProviderKeysStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** 提供方密钥页（形态与 [SecurityComponent] 完全一致，见 ADR-069）。 */
interface ProviderKeysComponent {
    val state: StateFlow<ProviderKeysState>
    val effects: Flow<ProviderKeysEffect>
    fun onIntent(intent: ProviderKeysIntent)

    /** 后端就绪状态变化时由 UI 调（未就绪时 `Appear` 是空操作）。 */
    fun onConnectedChanged(connected: Boolean) = onIntent(ProviderKeysIntent.Appear(connected))
}

class DefaultProviderKeysComponent(
    private val componentContext: ComponentContext,
    gateway: ProviderKeysGateway,
    private val profile: String?,
    executorContext: CoroutineContext = Dispatchers.Default,
) : ProviderKeysComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder: StoreHolder<ProviderKeysState, ProviderKeysIntent, ProviderKeysEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = ProviderKeysStoreFactory.create(
                    gateway = gateway,
                    profile = profile,
                    executorContext = executorScope.coroutineContext,
                ),
                executorContext = executorScope.coroutineContext,
            )
        }

    override val state: StateFlow<ProviderKeysState> = holder.state

    override val effects: Flow<ProviderKeysEffect> = holder.labels

    override fun onIntent(intent: ProviderKeysIntent) = holder.dispatch(intent)

    override fun toString(): String = "ProviderKeysComponent(profile=$profile)"

    private companion object {
        const val STORE_KEY = "provider-keys"
    }
}
