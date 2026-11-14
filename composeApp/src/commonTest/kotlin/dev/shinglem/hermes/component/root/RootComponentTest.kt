package dev.shinglem.hermes.component.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RootComponent` 的导航语义（S5）。
 *
 * 这些断言锁的是**迁移前 `AppShell` 的隐式行为**，换骨架时最容易悄悄弄丢：
 * 1. 切 destination 用"替换整栈"而不是 push —— 否则连点三次会越积越深，
 *    `back()` 要按很多次才能回退；
 * 2. 重复选同一页是幂等的；
 * 3. 栈底 `back()` 是 no-op（不能让用户退出应用）。
 */
class RootComponentTest {

    private val lifecycle = LifecycleRegistry()

    @AfterTest
    fun tearDown() {
        if (lifecycle.state != Lifecycle.State.DESTROYED) lifecycle.destroy()
    }

    private fun component(initial: RootDestination = RootDestination.Sessions): RootComponent {
        lifecycle.resume()
        return DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle),
            initialProfile = "default",
            initial = initial,
        )
    }

    @Test
    fun `初始 destination 是传入的那个`() {
        val component = component()
        assertEquals(RootDestination.Sessions, component.active.value)
        assertEquals(RootDestination.Sessions, component.stack.value.active.configuration)
    }

    @Test
    fun `切换 destination 后栈只有一项`() {
        val component = component()
        component.select(RootDestination.Settings)
        assertEquals(RootDestination.Settings, component.active.value)
        assertEquals(1, component.stack.value.items.size, "顶层导航必须是替换语义，不能越点越深")
    }

    @Test
    fun `重复选择同一页是幂等的`() {
        val component = component()
        component.select(RootDestination.Capabilities)
        component.select(RootDestination.Capabilities)
        assertEquals(1, component.stack.value.items.size)
        assertEquals(RootDestination.Capabilities, component.active.value)
    }

    @Test
    fun `栈底 back 是 no-op`() {
        val component = component()
        component.back()
        assertEquals(RootDestination.Sessions, component.active.value)
        assertEquals(1, component.stack.value.items.size, "栈底不能 pop 掉唯一一项")
    }

    @Test
    fun `profile 透传`() {
        assertEquals("default", component().profile.value)
    }

    @Test
    fun `三次切换后仍只有一项`() {
        val component = component()
        listOf(RootDestination.Settings, RootDestination.Capabilities, RootDestination.Sessions)
            .forEach { component.select(it) }
        assertEquals(RootDestination.Sessions, component.active.value)
        assertEquals(1, component.stack.value.items.size)
    }

    @Test
    fun `子节点按 destination 分派`() {
        val component = component()
        assertTrue(component.stack.value.active.instance is RootChild.Sessions)

        component.select(RootDestination.Capabilities)
        assertTrue(component.stack.value.active.instance is RootChild.Capabilities)

        component.select(RootDestination.Settings)
        assertTrue(component.stack.value.active.instance is RootChild.Settings)
    }
}