package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.settings.store.ModelSettingsStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** 模型配置页（主 / 辅 / 备用三块共用一份状态，形态同 [SecurityComponent]）。 */
interface ModelSettingsComponent {
    val state: StateFlow<ModelSettingsState>
    val effects: Flow<ModelSettingsEffect>
    fun onIntent(intent: ModelSettingsIntent)

    fun onConnectedChanged(connected: Boolean) = onIntent(ModelSettingsIntent.Appear(connected))
}

class DefaultModelSettingsComponent(
    private val componentContext: ComponentContext,
    gateway: ModelSettingsGateway,
    private val profile: String?,
    executorContext: CoroutineContext = Dispatchers.Default,
) : ModelSettingsComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder: StoreHolder<ModelSettingsState, ModelSettingsIntent, ModelSettingsEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = ModelSettingsStoreFactory.create(
                    gateway = gateway,
                    profile = profile,
                    executorContext = executorScope.coroutineContext,
                ),
                executorContext = executorScope.coroutineContext,
            )
        }

    override val state: StateFlow<ModelSettingsState> = holder.state

    override val effects: Flow<ModelSettingsEffect> = holder.labels

    override fun onIntent(intent: ModelSettingsIntent) = holder.dispatch(intent)

    override fun toString(): String = "ModelSettingsComponent(profile=$profile)"

    private companion object {
        const val STORE_KEY = "model-settings"
    }
}
