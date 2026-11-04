package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.CustomEndpointProbeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 自定义端点表单 → 请求体的转换，以及探测结果的呈现口径。 */
class CustomEndpointLogicTest {

    @Test
    fun `apiKey 留空时传 null 而不是空串`() {
        val update = EndpointDraft(name = "本地", baseUrl = "http://x/v1", model = "m").toUpdate()
        assertNull(update.apiKey, "空串会让后端清掉已保存的键")
    }

    @Test
    fun `apiKey 填了就原样提交`() {
        val update = EndpointDraft(name = "本地", baseUrl = "http://x/v1", model = "m", apiKey = "sk-1").toUpdate()
        assertEquals("sk-1", update.apiKey)
    }

    @Test
    fun `必填项去空白，上下文长度非数字按留空处理`() {
        val update = EndpointDraft(
            name = "  本地  ",
            baseUrl = " http://x/v1 ",
            model = " m ",
            contextDraft = "abc",
        ).toUpdate()
        assertEquals("本地", update.name)
        assertEquals("http://x/v1", update.baseUrl)
        assertEquals("m", update.model)
        assertNull(update.contextLength)
    }

    @Test
    fun `上下文长度 0 视为自动`() {
        assertNull(EndpointDraft(name = "n", baseUrl = "u", model = "m", contextDraft = "0").toUpdate().contextLength)
        assertEquals(
            32000,
            EndpointDraft(name = "n", baseUrl = "u", model = "m", contextDraft = "32000").toUpdate().contextLength,
        )
    }

    @Test
    fun `canSubmit 要求名称、地址、模型三者都有值`() {
        assertFalse(EndpointDraft().canSubmit)
        assertFalse(EndpointDraft(name = "n", baseUrl = "u").canSubmit)
        assertTrue(EndpointDraft(name = "n", baseUrl = "u", model = "m").canSubmit)
    }

    @Test
    fun `探测成功后把地址替换成真正供出模型的 base`() {
        val draft = EndpointDraft(name = "n", baseUrl = "http://x", model = "m")
        val resolved = resolveProbeUrl(draft, "http://x/v1")
        assertEquals("http://x/v1", resolved?.baseUrl)
    }

    @Test
    fun `地址本来就一致或后端没给 resolved 时不动草稿`() {
        val draft = EndpointDraft(name = "n", baseUrl = "http://x/v1", model = "m")
        assertEquals(draft, resolveProbeUrl(draft, "http://x/v1/"))
        assertEquals(draft, resolveProbeUrl(draft, null))
        assertNull(resolveProbeUrl(null, "http://x/v1"))
    }

    @Test
    fun `探测提示区分成功、警告与连不上`() {
        assertEquals(
            "连接正常：发现 3 个模型",
            probeMessage(CustomEndpointProbeDto(ok = true, reachable = true, models = listOf("a", "b", "c"))),
        )
        assertEquals(
            "端点在，但没有模型",
            probeMessage(CustomEndpointProbeDto(ok = false, reachable = true, message = "端点在，但没有模型")),
        )
        assertEquals(
            "连不上端点",
            probeMessage(CustomEndpointProbeDto(ok = false, reachable = false)),
        )
    }

    @Test
    fun `传输模式标签不认识时回落原值`() {
        assertEquals("自动探测", apiModeLabel(""))
        assertEquals("Chat Completions", apiModeLabel("chat_completions"))
        assertEquals("weird_mode", apiModeLabel("weird_mode"))
    }
}
