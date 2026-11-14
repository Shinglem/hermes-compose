package dev.shinglem.hermes.component.updater

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.updater.store.UpdaterStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * 「关于」页的检查更新（S5：从 `UpdaterViewModel` 迁来）。
 *
 * **没有 `effects`**：这一页只有状态，没有一次性事件（不弹 toast、不跳页面）。
 * 保留 `effects: Flow<Nothing>` 是为了与其它 6 个 Component 保持同一种形状 ——
 * 上层写 `LaunchedEffect { component.effects.collect { } }` 时不用做特例判断。
 */
interface UpdaterComponent {
    val state: StateFlow<UpdaterState>
    val effects: Flow<Nothing>
    fun onIntent(intent: UpdaterIntent)
}

/**
 * 默认实现。
 *
 * [executorContext] 生产用 `Dispatchers.Default`：这里是 REST 调用，不该占 UI 线程。
 */
class DefaultUpdaterComponent(
    private val componentContext: ComponentContext,
    gateway: UpdaterGateway,
    executorContext: CoroutineContext = Dispatchers.Default,
) : UpdaterComponent, ComponentContext by componentContext {

    private val holder: StoreHolder<UpdaterState, UpdaterIntent, Nothing> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = UpdaterStoreFactory.create(gateway, executorContext),
                executorContext = executorContext,
            )
        }

    override val state: StateFlow<UpdaterState> = holder.state

    override val effects: Flow<Nothing> = emptyFlow()

    override fun onIntent(intent: UpdaterIntent) = holder.dispatch(intent)

    override fun toString(): String = "UpdaterComponent"

    private companion object {
        const val STORE_KEY = "updater"
    }
}