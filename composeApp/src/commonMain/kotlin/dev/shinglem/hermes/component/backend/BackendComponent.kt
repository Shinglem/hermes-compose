package dev.shinglem.hermes.component.backend

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.backend.store.BackendStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** 后端管理页（S5：从 `BackendAdminViewModel` 迁来）。 */
interface BackendComponent {
    val state: StateFlow<BackendState>
    val effects: Flow<BackendEffect>
    fun onIntent(intent: BackendIntent)
}

/**
 * 默认实现。
 *
 * [executorContext] 用 `Dispatchers.Default`：这一页的动作是 `start()` / `stop()`
 * 这类可能跑几十秒的后端操作，绝不能占 UI 线程。
 */
class DefaultBackendComponent(
    private val componentContext: ComponentContext,
    gateway: BackendGateway,
    executorContext: CoroutineContext = Dispatchers.Default,
) : BackendComponent, ComponentContext by componentContext {

    private val holder: StoreHolder<BackendState, BackendIntent, BackendEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = BackendStoreFactory.create(gateway, executorContext),
                executorContext = executorContext,
            )
        }

    override val state: StateFlow<BackendState> = holder.state

    init {
        // 创建即订阅：MVIKotlin 的 Executor 不能在构造期 dispatch，所以用 intent 触发。
        holder.dispatch(BackendIntent.Observe)
    }

    override val effects: Flow<BackendEffect> = holder.labels

    override fun onIntent(intent: BackendIntent) = holder.dispatch(intent)

    override fun toString(): String = "BackendComponent"

    private companion object {
        const val STORE_KEY = "backend"
    }
}