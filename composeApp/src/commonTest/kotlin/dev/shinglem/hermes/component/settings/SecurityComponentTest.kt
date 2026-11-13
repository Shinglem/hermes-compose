package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.mvikotlin.core.store.Store
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.settings.ConfigFieldKind
import dev.shinglem.hermes.feature.settings.ConfigFieldSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S2 · 安全页 Component（模板样板的行为验证）。
 *
 * 这里断的是"模板真的能跑"，不是业务细节（那是 `SecurityReducerTest` 的事）：
 * 1. 造出来就自动读一次（不需要 UI 记得调 `onVisible()`）；
 * 2. 同 profile 再 `Appear` 不重复读；
 * 3. 编辑 → 保存会真的发 patch，成功后清掉 edits 并回读。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityComponentTest {

    private class FakeGateway(
        var config: JsonObject = buildJsonObject { put("approval.mode", "ask") },
        var failRead: Throwable? = null,
        var failPatch: Throwable? = null,
    ) : SecurityConfigGateway {
        var readCount = 0
        val patches = mutableListOf<JsonObject>()

        override suspend fun read(profile: String?): JsonObject {
            readCount++
            failRead?.let { throw it }
            return config
        }

        override suspend fun patch(profile: String?, patch: JsonObject) {
            failPatch?.let { throw it }
            patches += patch
        }
    }

    /** 每个用例一份干净的生命周期：`getOrCreate` 的缓存挂在它上面。 */
    private fun component(
        gateway: SecurityConfigGateway,
        profile: String? = "default",
    ): DefaultSecurityComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultSecurityComponent(
            componentContext = DefaultComponentContext(lifecycle),
            repository = gateway,
            profile = profile,
            executorContext = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `后端就绪后读取一次`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        assertEquals(0, gateway.readCount, "未就绪时不该发请求（后端 ready 前必失败）")

        subject.onConnectedChanged(true)
        assertEquals(1, gateway.readCount, "就绪后应读一次配置")
        assertEquals(Loadable.Ready(gateway.config), subject.state.value.config)
    }

    @Test
    fun `未就绪时不发请求`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(SecurityIntent.Appear(connected = false))
        assertEquals(0, gateway.readCount, "后端没就绪就请求，界面会先闪一条红色的连接失败")
    }

    @Test
    fun `装好就到 Ready 并记下 profile`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway, profile = "beta")
        subject.onConnectedChanged(true)
        val state = subject.state.value
        assertTrue(state.config is Loadable.Ready)
        assertEquals("beta", state.profile)
    }

    @Test
    fun `同 profile 再次 Appear 不重复读`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(SecurityIntent.Appear())
        subject.onIntent(SecurityIntent.Appear())
        assertEquals(1, gateway.readCount, "重复 Appear 不该重复请求后端")
    }

    @Test
    fun `Reload 强制重新读`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(SecurityIntent.Appear())
        subject.onIntent(SecurityIntent.Reload)
        assertEquals(2, gateway.readCount)
    }

    @Test
    fun `读取失败保留为 Failed 且不抛`() = runTest {
        val gateway = FakeGateway(failRead = AppError.Unknown("offline"))
        val subject = component(gateway)
        subject.onIntent(SecurityIntent.Appear())
        val config = subject.state.value.config
        assertTrue(config is Loadable.Failed, "读失败应落成 Failed 供 UI 显示：$config")
    }

    @Test
    fun `编辑后 dirty 为真`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(SecurityIntent.Appear())
        subject.onIntent(SecurityIntent.Edit("approval.mode", "always"))
        assertEquals("always", subject.state.value.edits["approval.mode"])
        assertTrue(subject.state.value.dirty)
    }

    @Test
    fun `保存发出稀疏 patch 并在成功后清空 edits`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        val spec = ConfigFieldSpec(path = "approval.mode", label = "审批", kind = ConfigFieldKind.TEXT)

        subject.onIntent(SecurityIntent.Appear())
        subject.onIntent(SecurityIntent.Edit("approval.mode", "always"))
        gateway.config = buildJsonObject { put("approval.mode", "always") }
        subject.onIntent(SecurityIntent.Save(listOf(spec)))

        assertEquals(1, gateway.patches.size, "保存应发出一次 patch")
        assertEquals(
            "always",
            gateway.patches.single()["approval"]?.let {
                (it as? JsonObject)?.get("mode")?.jsonPrimitive?.content
            },
        )
        val state = subject.state.value
        assertTrue(!state.dirty, "保存成功后应清空 edits")
        assertEquals("已保存", state.notice)
    }

    @Test
    fun `保存失败保留 edits 并给出错误`() = runTest {
        val gateway = FakeGateway(failPatch = AppError.Unknown("nope"))
        val subject = component(gateway)
        val spec = ConfigFieldSpec(path = "approval.mode", label = "审批", kind = ConfigFieldKind.TEXT)

        subject.onIntent(SecurityIntent.Appear())
        subject.onIntent(SecurityIntent.Edit("approval.mode", "always"))
        subject.onIntent(SecurityIntent.Save(listOf(spec)))

        val state = subject.state.value
        assertTrue(state.dirty, "保存失败后清空 edits 会让用户白填一遍")
        assertNotNull(state.error)
    }
}
