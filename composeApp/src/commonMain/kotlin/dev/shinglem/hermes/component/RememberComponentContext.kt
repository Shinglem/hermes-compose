package dev.shinglem.hermes.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
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
