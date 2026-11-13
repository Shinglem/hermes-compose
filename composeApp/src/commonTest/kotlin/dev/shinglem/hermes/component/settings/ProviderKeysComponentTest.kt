package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.shinglem.hermes.core.contract.EnvVarDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 · 提供方密钥页（模板的第二个实例）。
 *
 * 重点断的是**与 Security 不同的那部分**：drafts 里是**明文**，
 * 所以「保存成功后必须立刻清掉」和「换 profile 必须整份丢弃」这两条是硬要求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderKeysComponentTest {

    private class FakeGateway(
        var env: Map<String, EnvVarDto> = mapOf(
            "OPENAI_API_KEY" to EnvVarDto(
                description = "OpenAI GPT",
                isPassword = true,
                isSet = false,
                redactedValue = null,
                provider = "openai",
                providerLabel = "OpenAI",
                providerPrimary = true,
            ),
        ),
        var failRead: Throwable? = null,
        var failWrite: Throwable? = null,
    ) : ProviderKeysGateway {
        var readCount = 0
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun envVars(profile: String?): Map<String, EnvVarDto> {
            readCount++
            failRead?.let { throw it }
            return env
        }

        override suspend fun setEnvVar(key: String, value: String, profile: String?) {
            failWrite?.let { throw it }
            writes += key to value
        }
    }

    private fun component(
        gateway: ProviderKeysGateway,
        profile: String? = "default",
    ): DefaultProviderKeysComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultProviderKeysComponent(
            componentContext = DefaultComponentContext(lifecycle),
            gateway = gateway,
            profile = profile,
            executorContext = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `未就绪时不发请求`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        assertEquals(0, gateway.readCount, "后端没就绪就请求，界面会先闪连接失败")

        subject.onIntent(ProviderKeysIntent.Appear(connected = false))
        assertEquals(0, gateway.readCount)
    }

    @Test
    fun `就绪后读取一次`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        assertEquals(1, gateway.readCount)
        assertTrue(subject.state.value.env is Loadable.Ready)
    }

    @Test
    fun `重复 Appear 不重复读`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        subject.onIntent(ProviderKeysIntent.Appear())
        assertEquals(1, gateway.readCount)
    }

    @Test
    fun `读取失败落成 Failed`() = runTest {
        val gateway = FakeGateway(failRead = AppError.Unknown("offline"))
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        assertTrue(subject.state.value.env is Loadable.Failed)
    }

    @Test
    fun `编辑写入 drafts`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        subject.onIntent(ProviderKeysIntent.Edit("OPENAI_API_KEY", "sk-test"))
        assertEquals("sk-test", subject.state.value.drafts["OPENAI_API_KEY"])
    }

    @Test
    fun `保存成功后清掉明文`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        subject.onIntent(ProviderKeysIntent.Edit("OPENAI_API_KEY", "sk-test"))
        subject.onIntent(ProviderKeysIntent.Save("OPENAI_API_KEY"))

        assertEquals(listOf("OPENAI_API_KEY" to "sk-test"), gateway.writes)
        val state = subject.state.value
        assertNull(state.drafts["OPENAI_API_KEY"], "保存成功后必须清掉明文，客户端不缓存凭据")
        assertEquals("OPENAI_API_KEY", state.savedKey)
        assertNull(state.busyKey)
    }

    @Test
    fun `空白输入不触发保存`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        subject.onIntent(ProviderKeysIntent.Edit("OPENAI_API_KEY", "   "))
        subject.onIntent(ProviderKeysIntent.Save("OPENAI_API_KEY"))
        assertTrue(gateway.writes.isEmpty(), "全是空白不该发请求")
    }

    @Test
    fun `保存失败保留明文并给出错误`() = runTest {
        val gateway = FakeGateway(failWrite = AppError.Unknown("nope"))
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        subject.onIntent(ProviderKeysIntent.Edit("OPENAI_API_KEY", "sk-test"))
        subject.onIntent(ProviderKeysIntent.Save("OPENAI_API_KEY"))

        val state = subject.state.value
        assertEquals("sk-test", state.drafts["OPENAI_API_KEY"], "保存失败后清掉明文会让用户白填")
        assertNotNull(state.error)
        assertNull(state.busyKey)
    }

    @Test
    fun `换 profile 丢掉明文`() = runTest {
        val gateway = FakeGateway()
        val alpha = component(gateway, profile = "alpha")
        alpha.onIntent(ProviderKeysIntent.Appear())
        alpha.onIntent(ProviderKeysIntent.Edit("OPENAI_API_KEY", "sk-alpha"))
        assertEquals("sk-alpha", alpha.state.value.drafts["OPENAI_API_KEY"])

        // 换 profile 会重建 Component（上层 key(profile)），新实例上是空 drafts。
        val beta = component(gateway, profile = "beta")
        beta.onIntent(ProviderKeysIntent.Appear())
        assertTrue(beta.state.value.drafts.isEmpty(), "新 profile 的组件不该带上一个仓库的明文")
    }

    @Test
    fun `搜索词过滤分组`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ProviderKeysIntent.Appear())
        val before = subject.state.value.groups().sumOf { it.entries.size }
        assertTrue(before > 0)

        subject.onIntent(ProviderKeysIntent.SetQuery("OPENAI"))
        val after = subject.state.value.groups().sumOf { it.entries.size }
        assertEquals(1, after, "搜索应只留下匹配项")

        subject.onIntent(ProviderKeysIntent.SetQuery("绝不存在的键"))
        assertEquals(0, subject.state.value.groups().sumOf { it.entries.size })
    }
}
