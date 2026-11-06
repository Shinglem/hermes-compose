package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.contract.ClientPrompt
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `session.create` 的两处「客户端元数据」：`source` 与 `client_env`。
 *
 * 键名写错不会报错 —— 后端按白名单过滤，不认的键**静默丢弃**，于是提示词悄悄不生效。
 * 所以这里钉住键名本身，而不只是「有没有这个字段」。
 */
class CreateSessionParamsTest {

    @Test
    fun `session create 带上会话面与渲染提示词`() {
        val params = createSessionParams(profile = null)
        assertEquals("tui", params["source"]?.jsonPrimitive?.content, "会话面必须是 tui")

        val env = params["client_env"]?.jsonObject
        assertNotNull(env, "client_env 缺失，提示词不会到达后端")
        // 后端白名单里的确切名字（agent/session_client_env.py::BEHAVIOUR_ENV）
        assertEquals("HERMES_EPHEMERAL_SYSTEM_PROMPT", ClientPrompt.ENV_KEY)
        val prompt = env[ClientPrompt.ENV_KEY]?.jsonPrimitive?.content
        assertNotNull(prompt, "client_env 里没有 ${ClientPrompt.ENV_KEY}，会被后端静默丢弃")
        assertTrue(prompt.isNotBlank(), "提示词为空")
    }

    @Test
    fun `提示词如实描述渲染能力并禁止 MEDIA 标签`() {
        val prompt = ClientPrompt.RENDERING
        // 纠正「这只是终端」的自我审查
        assertTrue(prompt.contains("not a plain terminal"), "缺少终端澄清：$prompt")
        assertTrue(prompt.contains("Markdown"), "缺少 Markdown 能力说明")
        assertTrue(prompt.contains("LaTeX"), "缺少公式能力说明")
        // 保留「MEDIA: 不可用」这条正确约束
        assertTrue(prompt.contains("MEDIA:"), "缺少 MEDIA: 禁令")
        assertTrue(prompt.contains("NOT intercepted"), "MEDIA: 禁令措辞丢失")
        // 不要出现未替换的模板残留
        assertTrue(!prompt.contains("{{") && !prompt.contains("\${"), "提示词里有未处理的占位符")
    }

    @Test
    fun `模型与推理强度等可选参数仍然照旧传递`() {
        val params = createSessionParams(
            profile = "p1",
            title = "标题",
            provider = "volcengine",
            model = "m1",
            reasoningEffort = "high",
        )
        assertEquals("p1", params["profile"]?.jsonPrimitive?.content)
        assertEquals("标题", params["title"]?.jsonPrimitive?.content)
        assertEquals("volcengine", params["provider"]?.jsonPrimitive?.content)
        assertEquals("m1", params["model"]?.jsonPrimitive?.content)
        assertEquals("high", params["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `空的可选参数不写入`() {
        val params = createSessionParams(profile = null, title = "", provider = "  ")
        assertEquals(null, params["profile"])
        assertEquals(null, params["title"])
        assertEquals(null, params["provider"])
    }
}
