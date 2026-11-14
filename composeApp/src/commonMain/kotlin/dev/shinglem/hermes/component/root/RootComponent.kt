package dev.shinglem.hermes.component.root

import com.arkivanov.decompose.Child
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 顶层 Component（S5）。
 *
 * ### 职责边界（为什么这么窄）
 *
 * 迁移前的 `AppShell`（1093 行）同时做四件事：持有 9 个 ViewModel、决定布局、
 * 消费通知事件、以及"切页"本身。这里只保留**最后一件**：导航状态。
 *
 * 其余三件事各有归属：
 * - 布局 → 各 `*Content` Composable（它们从各自的 Component 取 state）；
 * - ViewModel → 迁成 Component（S2 已建好 7 个模板，Chat 已完成）；
 * - 通知 → `NotificationComponent`（下一步）。
 *
 * ### 三条来自 09 篇的硬约束
 *
 * 1. **rail 必须在 scaffold 外面**。它进 `extraPane` 后会被窄窗的
 *    `maxHorizontalPartitions` 收起，而 rail 是导航（切 profile / 切模式），不能消失（AGENTS.md 硬约束 11）。
 * 2. **`ChildStack` 只管生命周期不管布局**（这是 Decompose 的定位）。
 * 3. **切 destination 不重建子 Component**：`ChildStack` 会保留非栈顶子节点的状态
 *    （S0 的 `RefactorBaseSpikeTest` 验证过：压栈后 Store 仍推进）。
 *    所以从"会话"切到"设置"再切回来，会话不会被重开 —— 迁移前靠 `remember` 保住的正是这个。
 */
interface RootComponent {
    /** 当前 destination 栈（`active.configuration` 决定布局画哪一屏）。 */
    val stack: Value<ChildStack<RootDestination, RootChild>>

    /** 当前 destination（对 UI 友好的投影，省得每次 `stack.value.active.configuration`）。 */
    val active: StateFlow<RootDestination>

    val profile: StateFlow<String?>

    /** 切到某个 destination（rail 点击）。已在该页时不重复 push。 */
    fun select(destination: RootDestination)

    /** 返回上一页（详情面板的"返回"、Esc）。栈底时是 no-op。 */
    fun back()
}

/** 每个 destination 对应的子 Component。 */
sealed interface RootChild {
    val configuration: RootDestination

    /** 会话页。`S5` 第一步先留空实现，UI 仍走旧 `AppShell` 分支（见类注释）。 */
    data class Sessions(override val configuration: RootDestination) : RootChild

    data class Capabilities(override val configuration: RootDestination) : RootChild

    data class Settings(override val configuration: RootDestination) : RootChild
}

/**
 * 默认实现。
 *
 * **本步只搭骨架**：`Sessions` / `Capabilities` / `Settings` 三个子节点暂时是空壳
 * （不持有真实 Component）。先让 `ChildStack` 的生命周期语义跑通并测住，
 * 再把 `AppShell` 的分支逐个搬进来 —— 这样每一步都能编译 + 测试绿。
 */
class DefaultRootComponent(
    private val componentContext: ComponentContext,
    initialProfile: String? = null,
    initial: RootDestination = RootDestination.Sessions,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<RootDestination>()

    private val _profile = MutableStateFlow(initialProfile)
    override val profile: StateFlow<String?> = _profile.asStateFlow()

    override val stack: Value<ChildStack<RootDestination, RootChild>> =
        childStack(
            source = navigation,
            serializer = RootDestination.serializer(),
            initialConfiguration = initial,
            // Decompose 3.6.2 的 key 参数名是 `source`；childFactory 的 receiver
            // 静态类型必须写 `ComponentContext`（写具体类型会 receiver type mismatch，S0 踩过）。
            // 顶层导航不接管返回键（桌面端没有"返回键"，且详情面板自己要处理 Esc）。
            handleBackButton = false,
            childFactory = ::createChild,
        )

    private val _active = MutableStateFlow(initial)
    override val active: StateFlow<RootDestination> = _active.asStateFlow()

    private fun createChild(
        configuration: RootDestination,
        context: ComponentContext,
    ): RootChild = when (configuration) {
        RootDestination.Sessions -> RootChild.Sessions(configuration)
        RootDestination.Capabilities -> RootChild.Capabilities(configuration)
        RootDestination.Settings -> RootChild.Settings(configuration)
    }

    override fun select(destination: RootDestination) {
        if (stack.value.active.configuration == destination) return
        _active.value = destination
        // 顶层导航用"替换整栈"语义：不会越点越深。
        // 注意 `StackNavigator.navigate` 有**两个**参数（transformer + onComplete），
        // 只传 lambda 会被当成 `transformer` 而缺 `onComplete` —— Decompose 3.6.2 没有默认值。
        navigation.navigate(
            transformer = { listOf(destination) },
            onComplete = { _, _ -> },
        )
    }

    override fun back() {
        // 栈底不能退：`ChildStack` 的 `pop` 在只剩一个时是 no-op，但显式判一下更清楚。
        if (stack.value.items.size <= 1) return
        navigation.pop(onComplete = { })

        _active.value = stack.value.active.configuration
    }

    override fun toString(): String = "RootComponent(active=${_active.value})"
}
