package dev.shinglem.hermes.component.browser

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.browser.store.BrowserStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.CoroutineContext

/**
 * 浏览器侧栏（v0.6.1，ADR-082）。
 *
 * **只显示，不拥有**：这个 Component 的生命周期与"agent 的浏览器"无关 ——
 * 它销毁时那只浏览器必须继续跑（`BrowserEffect` 里没有 `Kill`，
 * `BrowserIntent` 里没有 `Launch`，这是刻意的）。
 */
interface BrowserComponent {
    val state: StateFlow<BrowserState>
    val effects: Flow<BrowserEffect>
    fun onIntent(intent: BrowserIntent)
}

/**
 * 默认实现。
 *
 * [executorContext] 生产用 `Dispatchers.IO`：发现要读目录 + 遍历窗口句柄，
 * 不该占 UI 线程。
 */
class DefaultBrowserComponent(
    private val componentContext: ComponentContext,
    gateway: BrowserGateway,
    executorContext: CoroutineContext = Dispatchers.IO,
) : BrowserComponent, ComponentContext by componentContext {

    private val holder: StoreHolder<BrowserState, BrowserIntent, BrowserEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = BrowserStoreFactory.create(gateway, executorContext),
                executorContext = executorContext,
            )
        }

    override val state: StateFlow<BrowserState> = holder.state

    override val effects: Flow<BrowserEffect> = holder.labels

    override fun onIntent(intent: BrowserIntent) = holder.dispatch(intent)

    override fun toString(): String = "BrowserComponent"

    private companion object {
        const val STORE_KEY = "browser"
    }
}

/** 给不需要浏览器的场景用的空实现（预览 / 测试）。 */
object NoopBrowserComponent : BrowserComponent {
    override val state: StateFlow<BrowserState> = kotlinx.coroutines.flow.MutableStateFlow(BrowserState())
    override val effects: Flow<BrowserEffect> = emptyFlow()
    override fun onIntent(intent: BrowserIntent) = Unit
}
