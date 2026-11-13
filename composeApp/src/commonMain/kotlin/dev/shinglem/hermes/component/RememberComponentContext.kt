package dev.shinglem.hermes.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume

/**
 * 一个随 composition 存活的根 [ComponentContext]。
 *
 * **S2 过渡件**：S5 落 `RootComponent` 之后，`ComponentContext` 由 `componentContext()` 扩展
 * 从 Decompose 的根注入，这个函数删除（`13 篇` §6.2）。
 *
 * 现在之所以需要它：settings 各域先一步改成 Component，但外层还是 `AppShell` +
 * androidx `viewModel {}` 的混合结构，没有 Decompose 的根。为了不把迁移拆成"必须一次全换"，
 * 这里造一个最小的根上下文挂在 composition 上。
 *
 * **生命周期要手动 `resume()`**：`DefaultComponentContext` 不会自己进入 RESUMED ——
 * 而 settings 域的 `Appear`（进页面即读）依赖 RESUMED 语义。S5 由 Decompose 的
 * 根自动推进后，这一句就该消失。
 */
@Composable
fun rememberDefaultComponentContext(): ComponentContext = remember {
    LifecycleRegistry().also { it.resume() }.let { DefaultComponentContext(it) }
}
