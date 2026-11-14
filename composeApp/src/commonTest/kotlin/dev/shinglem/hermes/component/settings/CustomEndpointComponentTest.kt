package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.shinglem.hermes.core.contract.CurrentModelDto
import dev.shinglem.hermes.core.contract.CustomEndpointActivatedDto
import dev.shinglem.hermes.core.contract.CustomEndpointDto
import dev.shinglem.hermes.core.contract.CustomEndpointProbeDto
import dev.shinglem.hermes.core.contract.CustomEndpointSavedDto
import dev.shinglem.hermes.core.contract.CustomEndpointUpdateDto
import dev.shinglem.hermes.core.contract.CustomEndpointsDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.settings.EndpointDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 · 自定义端点页。
 *
 * 这个域比前两个复杂：有「列表 ↔ 表单」两态、三条互斥的忙状态（保存/激活/删除），
 * 所以重点断的是**状态机不会串**：草稿不该被列表刷新清掉、删除要二次确认、
 * 激活后要重新拉列表。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomEndpointComponentTest {

    private fun endpoint(id: String = "ep-1") = CustomEndpointDto(
        id = id,
        name = "My Endpoint",
        baseUrl = "https://api.example.com/v1",
        model = "gpt-4o",
        apiMode = "openai",
        contextLength = 128000,
        discoverModels = true,
    )

    private class FakeGateway(
        var endpoints: List<CustomEndpointDto> = emptyList(),
        var current: CurrentModelDto = CurrentModelDto(),
        var probeOk: Boolean = true,
        var failList: Throwable? = null,
        var failUpsert: Throwable? = null,
        var failActivate: Throwable? = null,
        var failDelete: Throwable? = null,
    ) : CustomEndpointGateway {
        var listCount = 0
        var upserts = mutableListOf<CustomEndpointUpdateDto>()
        val activated = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var probeUrl: String? = null

        override suspend fun list(profile: String?): CustomEndpointsDto {
            listCount++
            failList?.let { throw it }
            return CustomEndpointsDto(endpoints = endpoints, current = current)
        }

        override suspend fun upsert(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointSavedDto {
            failUpsert?.let { throw it }
            upserts += update
            return CustomEndpointSavedDto(id = update.name, endpoints = endpoints, current = current)
        }

        override suspend fun validate(
            profile: String?,
            update: CustomEndpointUpdateDto,
        ): CustomEndpointProbeDto {
            return CustomEndpointProbeDto(ok = probeOk, resolvedBaseUrl = probeUrl)
        }

        override suspend fun activate(profile: String?, endpointId: String): CustomEndpointActivatedDto {
            failActivate?.let { throw it }
            activated += endpointId
            return CustomEndpointActivatedDto(provider = "custom", model = "gpt-4o")
        }

        override suspend fun delete(profile: String?, endpointId: String) {
            failDelete?.let { throw it }
            deleted += endpointId
        }
    }

    private fun component(
        gateway: CustomEndpointGateway,
        profile: String? = "default",
    ): DefaultCustomEndpointComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultCustomEndpointComponent(
            componentContext = DefaultComponentContext(lifecycle),
            gateway = gateway,
            profile = profile,
            executorContext = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `未就绪不发请求`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        assertEquals(0, gateway.listCount)
        subject.onIntent(CustomEndpointIntent.Appear(connected = false))
        assertEquals(0, gateway.listCount)
    }

    @Test
    fun `就绪后读取列表`() = runTest {
        val gateway = FakeGateway(endpoints = listOf(endpoint()))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        assertEquals(1, gateway.listCount)
        val state = subject.state.value
        assertEquals(1, state.endpoints.let { (it as? Loadable.Ready)?.value?.size })
    }

    @Test
    fun `新建打开空草稿`() = runTest {
        val subject = component(FakeGateway())
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartCreate)
        val draft = subject.state.value.draft
        assertNotNull(draft)
        assertTrue(draft.isNew)
        assertEquals("", draft.apiMode.ifBlank { "" })
    }

    @Test
    fun `编辑不带回明文`() = runTest {
        val subject = component(FakeGateway())
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartEdit(endpoint()))
        val draft = subject.state.value.draft
        assertNotNull(draft)
        assertEquals("", draft.apiKey, "编辑时不回显明文，留空表示保持原值")
        assertEquals("ep-1", draft.id)
    }

    @Test
    fun `取消编辑回到列表`() = runTest {
        val subject = component(FakeGateway())
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartCreate)
        subject.onIntent(CustomEndpointIntent.CancelEdit)
        assertNull(subject.state.value.draft)
    }

    @Test
    fun `保存成功后关闭草稿并给提示`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartCreate)
        val draft = EndpointDraft(
            name = "New EP",
            baseUrl = "https://api.example.com/v1",
            model = "gpt-4o",
            apiKey = "sk-1",
            apiMode = "openai",
        )
        subject.onIntent(CustomEndpointIntent.EditDraft(draft))
        subject.onIntent(CustomEndpointIntent.Save)

        assertEquals(1, gateway.upserts.size)
        val state = subject.state.value
        assertNull(state.draft, "保存成功后应回到列表")
        assertNull(state.busyKey)
        assertNotNull(state.notice)
    }

    @Test
    fun `保存失败保留草稿`() = runTest {
        val gateway = FakeGateway(failUpsert = AppError.Unknown("nope"))
        val subject = component(gateway)
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartCreate)
        subject.onIntent(
            CustomEndpointIntent.EditDraft(
                EndpointDraft(
                    name = "New EP",
                    baseUrl = "https://api.example.com/v1",
                    model = "gpt-4o",
                    apiKey = "sk-1",
                    apiMode = "openai",
                ),
            ),
        )
        subject.onIntent(CustomEndpointIntent.Save)

        val state = subject.state.value
        assertNotNull(state.draft, "保存失败后关掉草稿会让用户白填一遍")
        assertNotNull(state.error)
        assertNull(state.busyKey)
    }

    @Test
    fun `激活后重新拉列表`() = runTest {
        val gateway = FakeGateway(endpoints = listOf(endpoint()))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        val before = gateway.listCount
        subject.onIntent(CustomEndpointIntent.Activate("ep-1"))
        assertEquals(listOf("ep-1"), gateway.activated)
        assertTrue(gateway.listCount > before, "激活会改主模型槽位，必须重新拉一次")
    }

    @Test
    fun `删除需要二次确认`() = runTest {
        val gateway = FakeGateway(endpoints = listOf(endpoint()), failDelete = null)
        val subject = component(gateway)
        subject.onConnectedChanged(true)

        subject.onIntent(CustomEndpointIntent.AskDelete("ep-1"))
        assertEquals("ep-1", subject.state.value.pendingDelete)
        assertTrue(gateway.deleted.isEmpty(), "只点删除按钮不该立刻发请求")

        subject.onIntent(CustomEndpointIntent.DismissDelete)
        assertNull(subject.state.value.pendingDelete)
        assertTrue(gateway.deleted.isEmpty())

        subject.onIntent(CustomEndpointIntent.AskDelete("ep-1"))
        subject.onIntent(CustomEndpointIntent.ConfirmDelete)
        assertEquals(listOf("ep-1"), gateway.deleted)
        assertNull(subject.state.value.pendingDelete)
    }

    @Test
    fun `探测会把 base_url 换成解析后的地址`() = runTest {
        val gateway = FakeGateway()
        gateway.probeUrl = "https://api.example.com/v1"
        val subject = component(gateway)
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartCreate)
        subject.onIntent(
            CustomEndpointIntent.EditDraft(
                EndpointDraft(
                    name = "EP",
                    baseUrl = "https://api.example.com",
                    model = "gpt-4o",
                    apiKey = "sk-1",
                    apiMode = "openai",
                ),
            ),
        )
        subject.onIntent(CustomEndpointIntent.Validate)

        val state = subject.state.value
        assertEquals("https://api.example.com/v1", state.draft?.baseUrl, "探测后表单要显示最终会落盘的地址")
        assertEquals(true, state.probeOk)
        assertNotNull(state.probe)
    }

    @Test
    fun `换 profile 关掉正在编辑的草稿`() = runTest {
        val gateway = FakeGateway()
        val alpha = component(gateway, profile = "alpha")
        alpha.onIntent(CustomEndpointIntent.Appear())
        alpha.onIntent(CustomEndpointIntent.StartCreate)
        assertNotNull(alpha.state.value.draft)

        val beta = component(gateway, profile = "beta")
        beta.onIntent(CustomEndpointIntent.Appear())
        assertNull(beta.state.value.draft, "新 profile 不该继承上一个仓库的草稿")
    }

    /** 一次性事件（Label → effects）的发射序列用 Turbine 断言（验收 #12）。 */
    @Test
    fun `保存成功弹一次 Toast`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(CustomEndpointIntent.Appear())
        subject.onIntent(CustomEndpointIntent.StartCreate)
        subject.onIntent(
            CustomEndpointIntent.EditDraft(
                EndpointDraft(
                    name = "New EP",
                    baseUrl = "https://api.example.com/v1",
                    model = "gpt-4o",
                    apiKey = "sk-1",
                    apiMode = "openai",
                ),
            ),
        )

        subject.effects.test {
            subject.onIntent(CustomEndpointIntent.Save)
            assertEquals("已添加", (awaitItem() as CustomEndpointEffect.Toast).text)
            expectNoEvents()
        }
    }
}
