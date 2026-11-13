package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import dev.shinglem.hermes.component.StoreHolder
import dev.shinglem.hermes.component.settings.store.SecurityStoreFactory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * 安全三页（审批 / 隐私与网络 / 检查点）的 Component。
 *
 * 这是 S2 的**模板样板**，后面 6 个 settings 域照抄这个形状：
 *
 * ```
 * interface XxxComponent { state / effects / onIntent }     ← 对外只有这三样
 * class DefaultXxxComponent(ctx, deps) : XxxComponent, ComponentContext by ctx
 *     └─ instanceKeeper.getOrCreate { Store }                ← 存活期 = Component
 * ```
 *
 * 三条约束（`13 篇` §2.1）：
 * - 本文件**不 import Compose**（只有 `StateFlow` / `Flow`）；
 * - 有副作用的一律走 `onIntent`；
 * - 「什么时候读」由生命周期决定，不由 UI 里的 `LaunchedEffect(profile)` 决定
 *   （迁移前那个写法在 profile 切换时容易漏刷）。
 *
 * [profile] 变化时**重建整个 Component**（上层用 `key(profile)`），而不是在内部改字段 ——
 * 与迁移前 `setProfile` 彻底重置状态的语义一致，但不可能忘。
 */
interface SecurityComponent {
    val state: StateFlow<SecurityState>
    val effects: Flow<SecurityEffect>
    fun onIntent(intent: SecurityIntent)
}

/**
 * 默认实现。
 *
 * [executorContext] 只在测试里换 —— 生产用 `Dispatchers.Default`：
 * 页面上的请求都是 REST，不该占 UI 线程的调度器。
 */
class DefaultSecurityComponent(
    private val componentContext: ComponentContext,
    repository: SecurityConfigGateway,
    private val profile: String?,
    executorContext: CoroutineContext = Dispatchers.Default,
) : SecurityComponent, ComponentContext by componentContext {

    private val executorScope = CoroutineScope(SupervisorJob() + executorContext)

    private val holder: StoreHolder<SecurityState, SecurityIntent, SecurityEffect> =
        componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
            StoreHolder(
                store = SecurityStoreFactory.create(
                    repository = repository,
                    profile = profile,
                    executorContext = executorScope.coroutineContext,
                ),
                executorContext = executorScope.coroutineContext,
            )
        }

    override val state: StateFlow<SecurityState> = holder.state

    override val effects: Flow<SecurityEffect> = holder.labels

    override fun onIntent(intent: SecurityIntent) = holder.dispatch(intent)

    /**
     * 后端就绪状态变化时由 UI 调（`LaunchedEffect(connected)` 里）。
     *
     * 为什么不在这里的 `init` 里直接 `Appear`：Component 创建时后端往往还没 Ready，
     * 立刻请求必然失败并留下一条红色的"连不上后端服务"——迁移前 UI 用
     * `LaunchedEffect(profile, isConnected, mode)` 把关就是为了这个。
     * 现在把就绪信号显式传进来，`Appear` 在未就绪时是空操作。
     */
    fun onConnectedChanged(connected: Boolean) {
        onIntent(SecurityIntent.Appear(connected))
    }
    override fun toString(): String = "SecurityComponent(profile=$profile)"

    private companion object {
        /** 同一 Component 只有一个 Store；key 固定即可（profile 变化会换新 Component）。 */
        const val STORE_KEY = "security"
    }
}
