package dev.shinglem.hermes.component.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.shinglem.hermes.core.contract.MoaConfigDto
import dev.shinglem.hermes.core.contract.MoaConfigUpdateDto
import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.contract.MoaSlotDto
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
 * S2 · MoA 页。
 *
 * MoA 的状态机比前几个域更"结构化"：一份**整表**预设 + 选中项 + 默认项 + 生效项，
 * 四者互相牵连（删预设要顺带改默认/生效、保存提交的是整表）。
 * 这些规则原实现散在 `MoaViewModel` 的 8 个方法里，现在收到 [MoaExecutor] 一处。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoaComponentTest {

    private fun slot(provider: String = "openai", model: String = "gpt-4o") =
        MoaSlotDto(provider = provider, model = model)

    private fun preset(name: String = "default") = MoaPresetDto(
        referenceModels = listOf(slot()),
        aggregator = slot(),
        enabled = name == "default",
    )

    private fun config(presets: Map<String, MoaPresetDto> = mapOf("default" to preset())) =
        MoaConfigDto(
            presets = presets,
            defaultPreset = "default",
            activePreset = "default",
        )

    private class FakeGateway(
        var config: MoaConfigDto = MoaConfigDto(),
        var failRead: Throwable? = null,
        var failSave: Throwable? = null,
    ) : MoaGateway {
        var readCount = 0
        val saved = mutableListOf<MoaConfigUpdateDto>()

        override suspend fun moa(profile: String?): MoaConfigDto {
            readCount++
            failRead?.let { throw it }
            return config
        }

        override suspend fun saveMoa(profile: String?, update: MoaConfigUpdateDto): MoaConfigDto {
            failSave?.let { throw it }
            saved += update
            return config.copy(
                presets = update.presets,
                defaultPreset = update.defaultPreset,
                activePreset = update.activePreset,
            )
        }
    }

    private fun component(
        gateway: MoaGateway,
        profile: String? = "default",
    ): DefaultMoaComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        return DefaultMoaComponent(
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
        assertEquals(0, gateway.readCount)
        subject.onIntent(MoaIntent.Appear(connected = false))
        assertEquals(0, gateway.readCount)
    }

    @Test
    fun `就绪后读取并选中生效预设`() = runTest {
        val gateway = FakeGateway(
            config = config(
                mapOf(
                    "a" to preset("a"),
                    "b" to preset("b"),
                ),
            ).copy(activePreset = "b"),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        val state = subject.state.value
        assertTrue(state.config is Loadable.Ready)
        assertEquals("b", state.selected, "应优先选中后端正在生效的预设")
        assertEquals(2, state.presetNames.size)
    }

    @Test
    fun `新增预设以当前为模板并自动编号`() = runTest {
        val subject = component(FakeGateway(config = config()))
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.AddPreset)

        val state = subject.state.value
        assertEquals(2, state.presetNames.size)
        assertEquals("preset-1", state.selected)
        assertTrue(state.dirty)

        subject.onIntent(MoaIntent.AddPreset)
        assertEquals(3, subject.state.value.presetNames.size)
        assertTrue("preset-2" in subject.state.value.presetNames)
    }

    @Test
    fun `删除预设时顺带修默认与生效`() = runTest {
        val gateway = FakeGateway(
            config = config(mapOf("default" to preset(), "extra" to preset("extra"))),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.Select("default"))
        subject.onIntent(MoaIntent.DeletePreset)

        val state = subject.state.value
        assertEquals(listOf("extra"), state.presetNames)
        assertEquals("extra", state.defaultPreset, "删掉默认预设后默认要改到剩下的那个")
        assertEquals("", state.activePreset, "删掉生效预设后要把生效项清空")
    }

    @Test
    fun `只剩一个预设时拒绝删除`() = runTest {
        val subject = component(FakeGateway(config = config()))
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.DeletePreset)
        assertEquals(1, subject.state.value.presetNames.size)
        assertNotNull(subject.state.value.error)
    }

    @Test
    fun `加参考模型复制最后一个槽位`() = runTest {
        val subject = component(FakeGateway(config = config()))
        subject.onConnectedChanged(true)
        val before = subject.state.value.preset!!.referenceModels.size
        subject.onIntent(MoaIntent.AddReference)
        val after = subject.state.value.preset!!.referenceModels.size
        assertEquals(before + 1, after)
        assertTrue(subject.state.value.dirty)
    }

    @Test
    fun `删参考模型按下标`() = runTest {
        val gateway = FakeGateway(
            config = config(
                mapOf(
                    "default" to preset().copy(
                        referenceModels = listOf(slot(model = "m1"), slot(model = "m2")),
                    ),
                ),
            ),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.RemoveReference(0))
        val models = subject.state.value.preset!!.referenceModels.map { it.model }
        assertEquals(listOf("m2"), models)
    }

    @Test
    fun `保存前拒绝半填预设`() = runTest {
        val gateway = FakeGateway(
            config = config(
                mapOf(
                    "default" to preset().copy(
                        referenceModels = listOf(slot(provider = "")),
                    ),
                ),
            ),
        )
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.Save)

        assertTrue(gateway.saved.isEmpty(), "半填预设不该发到后端")
        assertNotNull(subject.state.value.error, "应在前端先拦下并给出原因")
    }

    @Test
    fun `保存提交整份预设表`() = runTest {
        val gateway = FakeGateway(config = config())
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.AddPreset)
        subject.onIntent(MoaIntent.Save)

        assertEquals(1, gateway.saved.size)
        assertEquals(2, gateway.saved.single().presets.size, "后端以整表为准，不能只提交当前选中的")
        assertTrue(!subject.state.value.dirty, "保存成功后应清掉 dirty")
        assertNotNull(subject.state.value.notice)
    }

    @Test
    fun `保存失败保留编辑`() = runTest {
        val gateway = FakeGateway(config = config(), failSave = AppError.Unknown("nope"))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        subject.onIntent(MoaIntent.AddPreset)
        assertTrue(subject.state.value.dirty, "前置条件：新增预设后应为脏")
        subject.onIntent(MoaIntent.Save)

        val state = subject.state.value
        assertTrue(state.dirty, "保存失败后清掉 dirty 会让用户以为已经存上了")
        assertNotNull(state.error)
        assertNull(state.busyKey)
    }

    @Test
    fun `换 profile 丢掉未保存编辑`() = runTest {
        val gateway = FakeGateway(config = config())
        val alpha = component(gateway, profile = "alpha")
        alpha.onConnectedChanged(true)
        alpha.onIntent(MoaIntent.AddPreset)
        assertTrue(alpha.state.value.dirty)

        val beta = component(gateway, profile = "beta")
        beta.onConnectedChanged(true)
        assertTrue(!beta.state.value.dirty, "新 profile 不该继承上一个仓库的编辑")
    }

    @Test
    fun `读取失败落成 Failed`() = runTest {
        val gateway = FakeGateway(failRead = AppError.Unknown("offline"))
        val subject = component(gateway)
        subject.onConnectedChanged(true)
        assertTrue(subject.state.value.config is Loadable.Failed)
    }
}
