package dev.shinglem.hermes.architecture

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.create
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S0 · 架构基座 spike（`09-refactor.md` §3 S0 第 2、3 步）。
 *
 * 这里验的是**整个 v0.6.0 的立论基础**，不是"库能引用"：
 *
 * 1. **Essenty 二进制兼容**：MVIKotlin 4.4.0 编译于 Essenty 2.5.0，Decompose 3.6.2 要求 2.7.1，
 *    Gradle 实际解析到 2.7.1（`dependencyInsight` 已证）。`getStore` 的签名收的是
 *    `InstanceKeeper` 接口 —— 本测试在真实的 2.7.1 上跑一次，真调才算过。
 * 2. **Store 活过 Component 重建**：`instanceKeeper.getStore { }` 第二次必须拿回同一实例。
 * 3. **压栈后 Store 仍在推进**（最关键）：Component 被压到后台、不再有 UI 时 Store 继续工作。
 *    这一条不成立，「切走会话继续跑」就还得手写 `SessionShadow`，Y 方案的前提就塌了。
 */
class RefactorBaseSpikeTest {

    private val storeFactory = DefaultStoreFactory()

    /**
     * 最简 Store：Intent 直接当 Message 走 Reducer。
     *
     * `StoreFactory.create(name, autoInit, initialState, reducer)` 这个扩展（`StoreFactoryExt.kt`）
     * 内部用 `BypassExecutor`，不需要写 Executor —— 这正是"纯函数 Reducer 可平移"的形态。
     */
    private fun counterStore(): Store<Int, Int, Nothing> =
        storeFactory.create(
            name = "counter",
            initialState = 0,
            reducer = object : Reducer<Int, Int> {
                override fun Int.reduce(msg: Int): Int = this + msg
            },
        )

    // ------------------------------------------------------------- 1 & 2

    @Test
    fun `Store 放在 instanceKeeper 里，重复获取返回同一实例`() {
        val lifecycle = LifecycleRegistry()
        val context = DefaultComponentContext(lifecycle)
        lifecycle.resume()

        var created = 0
        fun obtain(): Store<Int, Int, Nothing> = context.instanceKeeper.getStore {
            created++
            counterStore()
        }

        val first = obtain()
        val second = obtain()

        assertSame(first, second)
        assertEquals(1, created)
        assertEquals(0, first.state)

        first.accept(41)
        assertEquals(41, second.state)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Store 的 state 能转成 StateFlow（UI 侧心智不变）`() = runTest {
        val store = counterStore()
        assertEquals(0, store.stateFlow.value)
        store.accept(7)
        assertEquals(7, store.stateFlow.value)
    }

    // ---------------------------------------------------------------- 3

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Component 被压栈到后台后 Store 仍在推进`() {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        // childStack 的扩展签名是 `Ctx.childStack(...)`，要求 `Ctx : GenericComponentContext<Ctx>`；
        // ComponentContext 恰好是 GenericComponentContext<ComponentContext>，所以 receiver 的
        // 静态类型必须写 ComponentContext（写 DefaultComponentContext 会「receiver type mismatch」）。
        val root: ComponentContext = DefaultComponentContext(lifecycle)

        val navigation = StackNavigation<Config>()
        val stack: Value<ChildStack<*, Child>> = root.childStack(
            // 3.6.2 签名：childStack(source: NavigationSource<Event<C>>, serializer, initialConfiguration,
            //                     key, handleBackButton, childFactory)
            // 没有 3.5 及更早的 `ChildStack.Source`；参数名是 `source` 不是 `navigation`。
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Main,
            childFactory = { config: Config, ctx: ComponentContext ->
                when (config) {
                    Config.Main -> Child.Main(ctx, counterStore()) as Child
                    Config.Details -> Child.Details as Child
                }
            },
        )

        val main = stack.value.active.instance as Child.Main
        assertEquals(Lifecycle.State.RESUMED, main.lifecycle.state)
        assertEquals(1, stack.value.items.size)

        main.store.accept(2)
        assertEquals(2, main.store.state)

        // 压栈：Main 从「有 UI」变成「后台」，但实例与 Store 都不许重建
        navigation.push(Config.Details)
        assertEquals(2, stack.value.items.size)

        val mainAfter = stack.value.items.first().instance as Child.Main
        assertSame(main, mainAfter, "压栈后 Main 的实例被重建了（Decompose 承诺不销毁）")
        assertEquals(main.store, mainAfter.store, "压栈后 Store 被重建了")
        assertTrue(
            mainAfter.lifecycle.state != Lifecycle.State.DESTROYED,
            "压栈后 Main 仍应是 RESUMED 之外的活动状态",
        )

        // 后台期间继续推进
        mainAfter.store.accept(5)
        assertEquals(7, mainAfter.store.state)
        assertEquals(7, main.store.state, "前台句柄应看到后台推进的结果")

        // 出栈回来，语义不变
        navigation.pop()
        assertEquals(1, stack.value.items.size)
        val back = stack.value.active.instance as Child.Main
        assertSame(main, back)
        assertEquals(7, back.store.state)
    }

    @Test
    fun `生命周期在压栈与出栈时按 Decompose 语义变化`() {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val root: ComponentContext = DefaultComponentContext(lifecycle)
        val navigation = StackNavigation<Config>()
        lateinit var main: Child.Main

        val stack = root.childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Main,
            childFactory = { config: Config, ctx: ComponentContext ->
                when (config) {
                    Config.Main -> Child.Main(ctx, counterStore()).also { main = it } as Child
                    Config.Details -> Child.Details as Child
                }
            },
        )

        assertEquals(Lifecycle.State.RESUMED, main.lifecycle.state)

        navigation.push(Config.Details)
        // 压栈后 Main 有 UI → 无 UI：Decompose 语义是 CREATED（不销毁、不重建）
        assertEquals(Lifecycle.State.CREATED, main.lifecycle.state)

        navigation.pop()
        assertEquals(Lifecycle.State.RESUMED, main.lifecycle.state)
    }

    // ---------------------------------------------------------------- 4

    @Test
    fun `ChildPanels 三档模式存在于 3_6_2 公开 API`() {
        // javap 实证：ChildPanelsMode.SINGLE / DUAL / TRIPLE；
        // ChildPanels(main, details, extra, mode)。真正的渲染由 PanelsHost 在 S5 落地。
        assertEquals(
            listOf("SINGLE", "DUAL", "TRIPLE"),
            ChildPanelsMode.entries.map { it.name },
        )
    }

    // ---------------------------------------------------------------- 辅助

    @kotlinx.serialization.Serializable
    private sealed interface Config {
        @kotlinx.serialization.Serializable
        data object Main : Config

        @kotlinx.serialization.Serializable
        data object Details : Config
    }

    private sealed interface Child {
        class Main(ctx: ComponentContext, val store: Store<Int, Int, Nothing>) : Child {
            val lifecycle: Lifecycle = ctx.lifecycle
        }

        data object Details : Child
    }
}
