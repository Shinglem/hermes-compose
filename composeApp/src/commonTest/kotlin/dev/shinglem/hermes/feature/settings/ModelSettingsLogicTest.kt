package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.core.contract.ModelInfoAdminDto
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelEntry
import dev.shinglem.hermes.feature.model.ModelProviderGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 模型配置页的纯逻辑：上下文覆盖的稀疏 patch、辅助槽位的 auto 判定、脏态判定。 */
class ModelSettingsLogicTest {

    @Test
    fun `上下文 patch 只写顶层 model_context_length，0 表示自动`() {
        assertEquals(0L, contextLengthPatch(null).contextValue())
        assertEquals(0L, contextLengthPatch(0).contextValue())
        assertEquals(200_000L, contextLengthPatch(200_000).contextValue())
    }

    @Test
    fun `上下文 patch 是稀疏的，不含其它键`() {
        val patch = contextLengthPatch(128_000)
        assertEquals(setOf("model_context_length"), patch.keys)
    }

    @Test
    fun `辅助槽位 auto 判定`() {
        assertTrue(AuxTaskDto(provider = "auto", model = "").isAuto)
        assertFalse(AuxTaskDto(provider = "deepseek", model = "deepseek-chat").isAuto)
        assertFalse(AuxTaskDto(provider = "auto", model = "deepseek-chat").isAuto)
    }

    @Test
    fun `选择与当前模型不一致时才算脏`() {
        val info = Loadable.Ready(ModelInfoAdminDto(model = "deepseek-flash", provider = "deepseek"))
        assertFalse(ModelSettingsState(info = info, provider = "deepseek", model = "deepseek-flash").selectionDirty)
        assertTrue(ModelSettingsState(info = info, provider = "deepseek", model = "deepseek-chat").selectionDirty)
        assertTrue(ModelSettingsState(info = info, provider = "openai", model = "deepseek-flash").selectionDirty)
    }

    @Test
    fun `provider 下拉只列有模型的组`() {
        val catalog = ModelCatalog(
            groups = listOf(
                ModelProviderGroup(
                    slug = "empty",
                    name = "Empty",
                    models = emptyList(),
                    authenticated = false,
                    warning = null,
                    isCurrent = false,
                ),
                ModelProviderGroup(
                    slug = "deepseek",
                    name = "DeepSeek",
                    models = listOf(ModelEntry("deepseek", "DeepSeek", "deepseek-chat", false, false)),
                    authenticated = true,
                    warning = null,
                    isCurrent = true,
                ),
            ),
            currentModel = null,
            currentProvider = null,
        )
        val state = ModelSettingsState(catalog = Loadable.Ready(catalog))
        assertEquals(listOf("deepseek" to "DeepSeek"), state.providerOptions)
        assertEquals(listOf("deepseek-chat"), state.modelsOf("deepseek").map { it.model })
        assertEquals(emptyList(), state.modelsOf("empty").map { it.model })
    }

    private fun JsonObject.contextValue(): Long =
        this["model_context_length"]!!.jsonPrimitive.content.toLong()
}
