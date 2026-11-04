package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.EnvVarDto
import dev.shinglem.hermes.core.result.Loadable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 提供方密钥的分组规则（纯函数，不碰网络）。
 *
 * 关键约束：`channel_managed` 的键必须被隐藏 —— 消息平台页也在改同一批键，
 * 两处都能写会让用户搞不清哪个生效。
 */
class ProviderKeyGroupingTest {

    @Test
    fun `只保留带 provider 的键与自定义键`() {
        val env = mapOf(
            "OPENROUTER_API_KEY" to env(provider = "openrouter", label = "OpenRouter"),
            "BROWSER_SESSION_TIMEOUT" to env(), // 工具类变量：无 provider、非 custom
            "MY_OWN_TOKEN" to env(custom = true),
        )

        val groups = groupProviderEnvVars(env)

        assertEquals(listOf("OpenRouter", "自定义键"), groups.map { it.label })
        assertEquals(listOf("OPENROUTER_API_KEY"), groups[0].entries.map { it.first })
        assertEquals(listOf("MY_OWN_TOKEN"), groups[1].entries.map { it.first })
    }

    @Test
    fun `隐藏由消息平台页管理的键`() {
        val env = mapOf(
            "TELEGRAM_BOT_TOKEN" to env(provider = "telegram", label = "Telegram", channelManaged = true),
            "OPENAI_API_KEY" to env(provider = "openai", label = "OpenAI"),
        )

        val groups = groupProviderEnvVars(env)

        assertEquals(1, groups.size)
        assertEquals("OpenAI", groups.first().label)
    }

    @Test
    fun `主键排在组内第一行，其余按键名字典序`() {
        val env = mapOf(
            "OPENAI_BASE_URL" to env(provider = "openai", label = "OpenAI"),
            "OPENAI_API_KEY" to env(provider = "openai", label = "OpenAI", primary = true),
            "OPENAI_ORG_ID" to env(provider = "openai", label = "OpenAI"),
        )

        val entries = groupProviderEnvVars(env).single().entries.map { it.first }

        assertEquals(listOf("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORG_ID"), entries)
    }

    @Test
    fun `组按 label 排序，label 为空时回落到 provider id`() {
        val env = mapOf(
            "Z_TEST" to env(provider = "zeta", label = ""),
            "A_TEST" to env(provider = "alpha", label = "Alpha"),
        )

        assertEquals(listOf("Alpha", "zeta"), groupProviderEnvVars(env).map { it.label })
    }

    @Test
    fun `没有自定义键时不产生空分组`() {
        val env = mapOf("OPENAI_API_KEY" to env(provider = "openai", label = "OpenAI"))

        assertFalse(groupProviderEnvVars(env).any { it.id == CUSTOM_GROUP_ID })
    }

    @Test
    fun `hasValue 兼容只给 redacted 预览的后端`() {
        assertTrue(env(isSet = true).hasValue())
        assertTrue(env(isSet = false, redacted = "sk-…abcd").hasValue())
        assertFalse(env().hasValue())
    }

    @Test
    fun `搜索按键名、说明与 provider 名过滤，并丢弃空组`() {
        val env = mapOf(
            "OPENAI_API_KEY" to env(provider = "openai", label = "OpenAI").copy(description = "GPT 系列"),
            "ANTHROPIC_API_KEY" to env(provider = "anthropic", label = "Anthropic"),
        )

        val byKey = ProviderKeysState(env = Loadable.Ready(env), query = "openai").groups()
        assertEquals(listOf("OpenAI"), byKey.map { it.label })

        val byDescription = ProviderKeysState(env = Loadable.Ready(env), query = "GPT").groups()
        assertEquals(listOf("OPENAI_API_KEY"), byDescription.single().entries.map { it.first })

        val noMatch = ProviderKeysState(env = Loadable.Ready(env), query = "zzz").groups()
        assertTrue(noMatch.isEmpty())
    }

    private fun env(
        isSet: Boolean = false,
        redacted: String? = null,
        provider: String = "",
        label: String = "",
        primary: Boolean = false,
        custom: Boolean = false,
        channelManaged: Boolean = false,
    ) = EnvVarDto(
        isSet = isSet,
        redactedValue = redacted,
        provider = provider,
        providerLabel = label,
        providerPrimary = primary,
        custom = custom,
        channelManaged = channelManaged,
    )
}
