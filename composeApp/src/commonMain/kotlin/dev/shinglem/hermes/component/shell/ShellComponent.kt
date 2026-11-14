package dev.shinglem.hermes.component.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.shell.store.ShellStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * Shell 的全局状态（S5：从 `ShellViewModel` 迁来）。
 *
 * 只管"全局那一份"：profile 列表、连接状态、后端状态、模式。
 * 具体每一页的状态在各自的 Component 里。
 */
interface ShellComponent {
    val state: StateFlow<ShellState>
    val effects: Flow<Nothing>
    fun onIntent(intent: ShellIntent)
}

class DefaultShellComponent(
    private val componentContext: ComponentContext,
    gateway: ShellGateway,
    initial: ShellState,
    executorContext: CoroutineContext = Dispatchers.Default,
) : ShellComponent, ComponentContext by componentContext {

    private val holder: StoreHolder<ShellState, ShellIntent, Nothing> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = ShellStoreFactory.create(gateway, executorContext, initial),
                executorContext = executorContext,
            )
        }

    override val state: StateFlow<ShellState> = holder.state

    override val effects: Flow<Nothing> = emptyFlow()

    init {
        // 创建即开始订阅（Executor 构造期不能 dispatch，所以走 intent）。
        holder.dispatch(ShellIntent.Observe)
    }

    override fun onIntent(intent: ShellIntent) = holder.dispatch(intent)

    override fun toString(): String = "ShellComponent"

    private companion object {
        const val STORE_KEY = "shell"
    }
}