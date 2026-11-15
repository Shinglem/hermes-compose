package dev.shinglem.hermes.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume

/**
 * 一个随 composition 存活的根 [ComponentContext]。
 *
 * **S2 过渡件**：S5 落 `RootComponent` 之后，`ComponentContext` 由 `componentContext()` 扩展
 * 从 Decompose 的根注入，这个函数删除（`13 篇` §6.2）。
 *
 * 现在之所以需要它：settings / chat 各域先一步改成 Component，但外层还是 `AppShell` +
 * androidx `viewModel {}` 的混合结构，没有 Decompose 的根。为了不把迁移拆成"必须一次全换"，
 * 这里造一个最小的根上下文挂在 composition 上。
 *
 * ### 生命周期必须手动推进，且**必须推进到 DESTROYED**
 *
 * `DefaultComponentContext` 不会自己进 RESUMED，而各 Component 的 `Appear`
 * （进页面即读）依赖 RESUMED 语义 —— 所以构造后立刻 `resume()`。
 *
 * 更关键的是**销毁**：`InstanceKeeper.onDestroy`、`StoreHolder` 的 `scope.cancel()`、
 * `ChatViewModel.dispose()`（退订 server-request handler + 取消待决的
 * `CompletableDeferred`）全部挂在 lifecycle 的 DESTROYED 上。
 * 只 `resume()` 不 `destroy()` 的话这些**一条都不会执行**，表现为：
 * - 离开聊天页后 `GatewayClient` 仍挂着旧的 approval/clarify handler；
 * - 在途的审批决策 `CompletableDeferred` 永远挂着。
 *
 * 所以用 `DisposableEffect` 在离开 composition 时 `destroy()`。
 *
 * **注意**：`LifecycleRegistry.destroy()` 是单向的，之后不能再 `resume()`。
 * 因此 `remember` 的 key 必须为空 —— 这个上下文要活到整个 AppShell 卸载，
 * 不能跟着 profile 之类会变的东西重建。
 */
@Composable
fun rememberDefaultComponentContext(): ComponentContext {
    val registry = remember { LifecycleRegistry().also { it.resume() } }
    val context = remember(registry) { DefaultComponentContext(registry) }
    DisposableEffect(registry) {
        onDispose {
            // 从 RESUMED 到 DESTROYED 会依次经过 PAUSED / STOPPED，Essenty 自动补中间态。
            registry.destroy()
        }
    }
    return context
}

/**
 * 一个**只给某个域用**的子上下文（[parent] 的子节点），随 [key] 变化重建。
 *
 * ### 为什么必须有它（实测事故）
 *
 * 各域 Component 都用 `componentContext.instanceKeeper.getOrCreate(key = "<域常量>")`
 * 持有 Store。若多个 Component 共用**同一个** `ComponentContext`，那么 `getOrCreate`
 * 的缓存键只有那个域常量 —— 与 profile 无关。于是：
 *
 * - 上层 `remember(context, activeProfile) { DefaultXxxComponent(..., profile) }`
 *   看起来"profile 变了就重建"，实际新建的只是外壳；
 * - 新 Component 从**旧** InstanceKeeper 里取回**旧 Store**（Executor 里 `profile`
 *   是构造参数 `val`），页面继续显示旧 profile 的配置，保存也写进旧 profile。
 *
 * `getOrCreate` 按 key 复用、不校验 factory，所以这种错配不会报错，只会静默串数据。
 *
 * `childContext` 会建一个**独立的** InstanceKeeper 与 Lifecycle，父级销毁时级联销毁 ——
 * 所以旧 profile 的 Store 会随旧子上下文一起 `onDestroy`，不会残留在图里。
 */
@Composable
fun rememberChildComponentContext(
    parent: ComponentContext,
    key: String,
): ComponentContext = remember(parent, key) { parent.childContext(key) }

/**
 * 按 domain + profile 派生一个子上下文并构建 Component（[rememberChildComponentContext] 的便捷封装）。
 *
 * 用于"这个 Component 内部用 `instanceKeeper.getOrCreate(key = <域常量>)` 持有 Store"的场景：
 * 子上下文的 InstanceKeeper 自带 domain+profile 前缀，所以换 profile 时**一定**拿到新 Store，
 * 旧 Store 随旧子上下文销毁。直接传根 context 会导致跨 profile 串数据（见
 * [rememberChildComponentContext] 的注释）。
 *
 * [factory] 只在 `own` 变化时才执行（`remember(own)`），所以闭包捕获的 `profile`
 * 与 `own` 里的 profile 始终一致。
 */
@Composable
fun <T> rememberPerProfileComponent(
    parent: ComponentContext,
    domain: String,
    profile: String?,
    factory: (ComponentContext) -> T,
): T {
    val own = rememberChildComponentContext(parent, "$domain:${profile ?: "default"}")
    return remember(own) { factory(own) }
}
