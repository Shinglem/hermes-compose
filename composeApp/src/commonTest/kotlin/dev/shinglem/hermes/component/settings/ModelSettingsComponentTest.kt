package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.shinglem.hermes.core.contract.AuxModelsDto
import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.core.contract.ModelInfoAdminDto
import dev.shinglem.hermes.core.contract.ModelSetResultDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelEntry
import dev.shinglem.hermes.feature.model.ModelProviderGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 · 模型配置页（主 / 辅助 / 备用三块共用一份 Store）。
 *
 * 重点断三件事：
 * 1. **四条数据线互不干扰**（目录失败不该把主模型也拖成失败）；
 * 2. **贵模型二次确认不能吞**（`confirm_required` 必须落成 `pending`，用户确认后才重发）；
 * 3. **备用模型的"重复不加"**（原实现有这条，去掉会让回退链出现两条一样的）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSettingsComponentTest {

    private fun entry(provider: String, model: String) = ModelEntry(
        provider = provider,
        providerName = provider,
        model = model,
        reasoning = false,
        free = false,
    )

    private fun catalog() = ModelCatalog(
        groups = listOf(
            ModelProviderGroup(
                slug = "openai",
                name = "OpenAI",
                models = listOf(entry("openai", "gpt-4o"), entry("openai", "o3")),
                authenticated = true,
                warning = null,
                isCurrent = false,
            ),
            ModelProviderGroup(
                slug = "anthropic",
                name = "Anthropic",
                models = listOf(entry("anthropic", "claude-4-sonnet")),
                authenticated = true,
                warning = null,
                isCurrent = false,
            ),
        ),
        currentModel = null,
        currentProvider = null,
    )

    private class FakeGateway(
        var info: ModelInfoAdminDto = ModelInfoAdminDto(provider = "openai", model = "gpt-4o"),
        var catalog: ModelCatalog = ModelCatalog(emptyList(), null, null),
        var aux: AuxModelsDto = AuxModelsDto(),
        var config: JsonObject = buildJsonObject { },
        var assignResult: ModelSetResultDto = ModelSetResultDto(ok = true),
        var failCatalog: Throwable? = null,
        var failAssign: Throwable? = null,
    ) : ModelSettingsGateway {
        var infoCount = 0
        var catalogCount = 0
        var auxCount = 0
        var fallbackCount = 0
        val assigns = mutableListOf<Triple<String, String, String>>()
        val patches = mutableListOf<JsonObject>()
        var resetCount = 0

        override suspend fun info(profile: String?): ModelInfoAdminDto {
            infoCount++
            return info
        }

        override suspend fun options(profile: String?, refresh: Boolean): ModelCatalog {
            catalogCount++
            failCatalog?.let { throw it }
            return catalog
        }

        override suspend fun auxiliary(profile: String?): AuxModelsDto {
            auxCount++
            return aux
        }

        override suspend fun assignModel(
            profile: String?,
            scope: String,
            provider: String,
            model: String,
            task: String?,
            confirmExpensive: Boolean,
        ): ModelSetResultDto {
            failAssign?.let { throw it }
            assigns += Triple(scope, provider, model)
            return assignResult
        }

        override suspend fun resetAuxiliary(profile: String?): ModelSetResultDto {
            resetCount++
            return ModelSetResultDto(ok = true)
        }

        override suspend fun readConfig(profile: String?): JsonObject {
            fallbackCount++
            return config
        }

        override suspend fun patchConfig(profile: String?, patch: JsonObject) {
            patches += patch
        }
    }

    private fun component(
        gateway: ModelSettingsGateway,
        profile: String? = "default",
    ): DefaultModelSettingsComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultModelSettingsComponent(
            componentContext = DefaultComponentContext(lifecycle),
            gateway = gateway,
            profile = profile,
            executorContext = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `就绪后四条线各拉一次`() = runTest {
        val gateway = FakeGateway(catalog = catalog())
        val subject = component(gateway)
        subject.onConnectedChanged(true)

        assertEquals(1, gateway.infoCount)
        assertEquals(1, gateway.catalogCount)
        assertEquals(1, gateway.auxCount)
        assertEquals(1, gateway.fallbackCount)
        val state = subject.state.value
        assertTrue(state.info is Loadable.Ready)
        assertTrue(state.catalog is Loadable.Ready)
    }

    @Test
    fun `未就绪不发请求`() = runTest {
        val gateway = FakeGateway()
        val subject = component(gateway)
        subject.onIntent(ModelSettingsIntent.Appear(connected = false))
        assertEquals(0, gateway.infoCount)
        assertEquals(0, gateway.catalogCount)
    }

    @Test
    fun `目录失败不影响主模型`() = runTest {
        val gateway = FakeGateway(catalog = catalog(), failCatalog = AppError.Unknown("catalog down"))
        val subject = component(gateway)
        subject.onConnectedChanged(true)

        val state = subject.state.value
        assertTrue(state.catalog is Loadable.Failed, "目录应单独失败")
        assertTrue(state.info is Loadable.Ready, "主模型不该被目录的失败拖累")
    }

    @Test
    fun `选 provider 时模型重置为该 provider 的第一个`() = runTest {
        val gateway = FakeGateway(catalog = catalog())
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ModelSettingsIntent.SelectProvider("anthropic"))

        val state = subject.state.value
        assertEquals("anthropic", state.provider)
        assertEquals("claude-4-sonnet", state.model)
    }

    @Test
    fun `贵模型二次确认落成 pending 且不刷新`() = runTest {
        val gateway = FakeGateway(
            catalog = catalog(),
            assignResult = ModelSetResultDto(
                ok = false,
                confirmRequired = true,
                confirmMessage = "o3 很贵，确认？",
            ),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        val infoBefore = gateway.infoCount

        subject.onIntent(ModelSettingsIntent.SelectModel("o3"))
        subject.onIntent(ModelSettingsIntent.ApplyMain)

        val state = subject.state.value
        assertNotNull(state.pending, "confirm_required 必须落成待确认，不能吞掉")
        assertEquals("o3 很贵，确认？", state.pending?.message)
        assertEquals(infoBefore, gateway.infoCount, "待确认阶段不该刷新 info")
    }

    @Test
    fun `确认后用 confirmExpensive 重发`() = runTest {
        val gateway = FakeGateway(catalog = catalog())
        gateway.assignResult = ModelSetResultDto(ok = true)
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ModelSettingsIntent.SelectModel("o3"))
        subject.onIntent(ModelSettingsIntent.ApplyMain)

        assertEquals(1, gateway.assigns.size)
        assertNull(subject.state.value.pending, "没有 confirm_required 就不该有待确认")
        assertNotNull(subject.state.value.notice)
    }

    @Test
    fun `编辑上下文只保留数字`() = runTest {
        val subject = component(FakeGateway(catalog = catalog()))
        subject.onConnectedChanged(true)
        subject.onIntent(ModelSettingsIntent.EditContext("12ab8000"))
        assertEquals("128000", subject.state.value.contextDraft)
    }

    @Test
    fun `没选模型时拒绝钉辅助任务`() = runTest {
        val gateway = FakeGateway(
            catalog = catalog(),
            info = ModelInfoAdminDto(provider = "", model = ""),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ModelSettingsIntent.AssignAuxTask(AuxTaskDto(task = "summarize")))

        assertTrue(gateway.assigns.isEmpty())
        assertNotNull(subject.state.value.error, "应给出先选一个模型的提示")
    }

    @Test
    fun `重置辅助模型会重新拉 aux`() = runTest {
        val gateway = FakeGateway(catalog = catalog())
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        val before = gateway.auxCount
        subject.onIntent(ModelSettingsIntent.ResetAux)

        assertEquals(1, gateway.resetCount)
        assertTrue(gateway.auxCount > before, "重置后要刷新辅助模型列表")
    }

    @Test
    fun `备用模型重复不加`() = runTest {
        val gateway = FakeGateway(catalog = catalog())
        val subject = component(gateway)
        subject.onConnectedChanged(true)

        subject.onIntent(ModelSettingsIntent.AddFallback)
        assertEquals(1, subject.state.value.fallback.let { (it as? Loadable.Ready)?.value?.size })

        subject.onIntent(ModelSettingsIntent.AddFallback)
        assertEquals(1, subject.state.value.fallback.let { (it as? Loadable.Ready)?.value?.size }, "重复项不该加进去")
        assertNotNull(subject.state.value.error)
    }

    @Test
    fun `保存备用模型发出 patch`() = runTest {
        val gateway = FakeGateway(catalog = catalog())
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ModelSettingsIntent.AddFallback)
        subject.onIntent(ModelSettingsIntent.SaveFallback)

        assertEquals(1, gateway.patches.size)
        assertNotNull(subject.state.value.notice)
    }

    @Test
    fun `指派失败保留错误且清忙`() = runTest {
        val gateway = FakeGateway(catalog = catalog(), failAssign = AppError.Unknown("nope"))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(ModelSettingsIntent.ApplyMain)

        val state = subject.state.value
        assertNull(state.busyKey)
        assertNotNull(state.error)
    }
}
