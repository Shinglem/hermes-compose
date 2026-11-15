package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S8 · 回归：**同一个 ComponentContext 上换 profile 必须换 Store**。
 *
 * ### 这条测试为什么必须存在（真出过事故）
 *
 * 各域 Component 都用 `instanceKeeper.getOrCreate(key = "<域常量>")` 持有 Store。
 * `getOrCreate` 按 key 复用、**不校验 factory**，所以只要多个 Component 共用同一个
 * ComponentContext，profile 参数就被完全忽略：
 *
 * - 上层 `remember(context, activeProfile) { DefaultSecurityComponent(..., profile) }`
 *   只是重建了外壳；
 * - 新 Component 从旧 InstanceKeeper 取回旧 Store（Executor 的 `profile` 是 `val`）；
 * - 页面继续显示旧 profile 的配置，**保存也写进旧 profile** —— 静默数据错乱。
 *
 * 迁移前的 `setProfile()` 会重置并重读，所以这是明确的行为回归。
 *
 * 修法是给每个域一个按 profile 派生的**子 ComponentContext**（`childContext`），
 * 它有独立的 InstanceKeeper。本测试把两种写法都跑一遍，钉住"只有子上下文才安全"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComponentContextIsolationTest {

    private class FakeGateway : SecurityConfigGateway {
        val readProfiles = mutableListOf<String?>()

        override suspend fun read(profile: String?): JsonObject {
            readProfiles += profile
            return buildJsonObject { put("profile", profile ?: "null") }
        }

        override suspend fun patch(profile: String?, patch: JsonObject) = Unit
    }

    private fun rootContext(): ComponentContext {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultComponentContext(lifecycle)
    }

    private fun security(
        context: ComponentContext,
        gateway: SecurityConfigGateway,
        profile: String?,
    ) = DefaultSecurityComponent(
        componentContext = context,
        repository = gateway,
        profile = profile,
        executorContext = UnconfinedTestDispatcher(),
    )

    @Test
    fun `共用根上下文时换 profile 会复用旧 Store（这是必须避免的退化形态）`() = runTest {
        val root = rootContext()
        val gateway = FakeGateway()

        security(root, gateway, "alpha").onConnectedChanged(true)
        // 同一个 root 再造一个 —— 就是修复前 AppShellDeps 的写法。
        security(root, gateway, "beta").onConnectedChanged(true)

        assertEquals(
            listOf<String?>("alpha"),
            gateway.readProfiles,
            "共用根上下文时 beta 根本不会去读：这正是修复前的 bug 形态",
        )
    }

    @Test
    fun `各域用 profile 派生的子上下文时换 profile 一定是新 Store`() = runTest {
        val root = rootContext()
        val gateway = FakeGateway()

        // 修复后的写法：域 + profile 作为子上下文 key。
        security(root.childContext("security:alpha"), gateway, "alpha").onConnectedChanged(true)
        security(root.childContext("security:beta"), gateway, "beta").onConnectedChanged(true)

        assertEquals(
            listOf<String?>("alpha", "beta"),
            gateway.readProfiles,
            "换 profile 必须重新读该 profile 的配置",
        )
    }

    @Test
    fun `同一子上下文键重复取 Component 复用同一 Store`() = runTest {
        val root = rootContext()
        val gateway = FakeGateway()
        val context = root.childContext("security:alpha")

        security(context, gateway, "alpha").onConnectedChanged(true)
        // 同一 key 再取一次（重组时会发生）：应当复用，不再重复请求。
        security(context, gateway, "alpha").onConnectedChanged(true)

        assertEquals(1, gateway.readProfiles.size, "同 profile 重建不该重复读后端")
    }

    @Test
    fun `子上下文的 Store 与根上下文的 Store 互不干扰`() = runTest {
        val root = rootContext()
        val gateway = FakeGateway()

        val child = security(root.childContext("security:alpha"), gateway, "alpha")
        child.onConnectedChanged(true)
        // 根上下文自己也建一个（其他域的场景）：不能与子上下文共享同一个 Store。
        val onRoot = security(root, gateway, "beta")
        onRoot.onConnectedChanged(true)

        assertEquals("alpha", child.state.value.profile)
        assertEquals("beta", onRoot.state.value.profile, "子上下文与父上下文必须是两个独立 Store")
        assertEquals(listOf<String?>("alpha", "beta"), gateway.readProfiles)
    }

    @Test
    fun `InstanceKeeper 按 key 复用不校验 factory`() {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val context = DefaultComponentContext(lifecycle)
        var created = 0

        class Inst(val name: String) : com.arkivanov.essenty.instancekeeper.InstanceKeeper.Instance {
            override fun onDestroy() = Unit
        }

        val first = context.instanceKeeper.getOrCreate(key = "k") {
            created++
            Inst("first")
        }
        val second = context.instanceKeeper.getOrCreate(key = "k") {
            created++
            Inst("second")
        }

        assertEquals("first", first.name)
        assertEquals(
            "first",
            second.name,
            "getOrCreate 复用旧值且不调用 factory —— 这就是跨 profile 串数据的机制本身",
        )
        assertEquals(1, created, "factory 只应被调用一次")
    }
}
