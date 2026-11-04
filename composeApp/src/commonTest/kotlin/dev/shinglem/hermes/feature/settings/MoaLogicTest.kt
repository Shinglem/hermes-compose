package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.contract.MoaSlotDto
import dev.shinglem.hermes.core.result.Loadable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MoA 页的保存前自检：后端会 422 拒绝半填预设，这里必须提前拦住。 */
class MoaLogicTest {

    private fun slot(provider: String, model: String) = MoaSlotDto(provider = provider, model = model)

    private fun preset(refs: List<MoaSlotDto>, aggregator: MoaSlotDto) = MoaPresetDto(
        referenceModels = refs,
        aggregator = aggregator,
    )

    @Test
    fun `完整预设没有问题`() {
        assertNull(
            moaPresetProblem(
                preset(listOf(slot("openai", "gpt-5")), slot("openrouter", "claude")),
            ),
        )
    }

    @Test
    fun `缺 provider 或模型的顾问会被指名`() {
        val problem = moaPresetProblem(preset(listOf(slot("openai", "gpt-5"), slot("", "x")), slot("a", "b")))
        assertNotNull(problem)
        assertTrue(problem.contains("参考模型 #2"), problem)
        assertTrue(problem.contains("provider"), problem)
    }

    @Test
    fun `没有顾问模型时不允许保存`() {
        val problem = moaPresetProblem(preset(emptyList(), slot("a", "b")))
        assertEquals("至少需要一个参考模型", problem)
    }

    @Test
    fun `聚合模型缺失也被拦住`() {
        val problem = moaPresetProblem(preset(listOf(slot("openai", "gpt-5")), MoaSlotDto()))
        assertNotNull(problem)
        assertTrue(problem.contains("聚合模型"), problem)
    }

    @Test
    fun `整份预设表里任何一个不完整都报出预设名`() {
        val problem = moaConfigProblem(
            mapOf(
                "good" to preset(listOf(slot("openai", "gpt-5")), slot("a", "b")),
                "bad" to preset(emptyList(), slot("a", "b")),
            ),
        )
        assertNotNull(problem)
        assertTrue(problem.contains("bad"), problem)
        assertEquals("至少要有一个预设", moaConfigProblem(emptyMap()))
    }

    @Test
    fun `fanout 标签认识内置档位与自定义 N`() {
        assertEquals("每个用户回合一次（默认，最省）", fanoutLabel("user_turn"))
        assertEquals("每 3 次工具迭代", fanoutLabel("every_n:3"))
        assertEquals("every_n:7", fanoutLabel("every_n:7"))
        assertEquals("user_turn", fanoutLabel(""))
    }

    @Test
    fun `温度显示去掉多余的零`() {
        assertEquals("1", trimNumber(1.0))
        assertEquals("0.7", trimNumber(0.7))
        assertEquals("2.35", trimNumber(2.35))
    }

    @Test
    fun `状态里选中的预设与问题同步`() {
        val safe = preset(listOf(slot("openai", "gpt-5")), slot("a", "b"))
        val state = MoaSettingsState(
            config = Loadable.Ready(dev.shinglem.hermes.core.contract.MoaConfigDto(presets = mapOf("main" to safe))),
            presets = mapOf("main" to safe),
            selected = "main",
        )
        assertEquals(safe, state.preset)
        assertEquals(listOf("main"), state.presetNames)
        assertNull(state.presetProblem)
    }
}
