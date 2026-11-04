package dev.shinglem.hermes.feature.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 备用模型链的读写：两种磁盘形状都要认，且写回时保持原形状。 */
class FallbackModelLogicTest {

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `单个 dict 形状读成一条，并标记非数组`() {
        val (entries, keepArray) = readFallbackProviders(
            parse("""{"fallback_providers": {"provider": "openai", "model": "gpt-5"}}"""),
        )
        assertEquals(listOf(FallbackEntry("openai", "gpt-5")), entries)
        assertFalse(keepArray)
    }

    @Test
    fun `数组形状读成多条，并标记为数组`() {
        val (entries, keepArray) = readFallbackProviders(
            parse(
                """
                {"fallback_providers": [
                  {"provider": "openai", "model": "gpt-5"},
                  {"provider": "deepseek", "model": "deepseek-flash"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(FallbackEntry("openai", "gpt-5"), FallbackEntry("deepseek", "deepseek-flash")),
            entries,
        )
        assertTrue(keepArray)
    }

    @Test
    fun `缺键与空数组都读成空链`() {
        assertEquals(emptyList(), readFallbackProviders(parse("{}")).first)
        assertEquals(emptyList(), readFallbackProviders(parse("""{"fallback_providers": []}""")).first)
        assertEquals(emptyList(), readFallbackProviders(parse("""{"fallback_providers": null}""")).first)
    }

    @Test
    fun `provider 或 model 缺失的条目不参与回退链`() {
        val (entries, _) = readFallbackProviders(
            parse("""{"fallback_providers": [{"provider": "openai"}, {"provider": "", "model": "x"}, {"model": "only"}]}"""),
        )
        assertEquals(emptyList(), entries)
    }

    @Test
    fun `单条且原形状是 dict 时仍写 dict`() {
        val patch = fallbackPatch(listOf(FallbackEntry("openai", "gpt-5")), keepArrayShape = false)
        assertIs<JsonObject>(patch["fallback_providers"])
        assertEquals(
            "openai",
            (patch["fallback_providers"] as JsonObject)["provider"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `原来就是数组时即使只有一条也写数组`() {
        val patch = fallbackPatch(listOf(FallbackEntry("openai", "gpt-5")), keepArrayShape = true)
        val array = assertIs<JsonArray>(patch["fallback_providers"])
        assertEquals(1, array.size)
    }

    @Test
    fun `多条时一律写数组，顺序即回退顺序`() {
        val patch = fallbackPatch(
            listOf(FallbackEntry("a", "m1"), FallbackEntry("b", "m2")),
            keepArrayShape = false,
        )
        val array = assertIs<JsonArray>(patch["fallback_providers"])
        assertEquals("a", (array[0] as JsonObject)["provider"]!!.jsonPrimitive.content)
        assertEquals("b", (array[1] as JsonObject)["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun `清空回退链写成空数组`() {
        val patch = fallbackPatch(emptyList(), keepArrayShape = false)
        val array = assertIs<JsonArray>(patch["fallback_providers"])
        assertTrue(array.isEmpty())
    }

    @Test
    fun `写回再读出是同一组条目`() {
        val original = listOf(FallbackEntry("openai", "gpt-5"), FallbackEntry("deepseek", "deepseek-flash"))
        val (roundTrip, keepArray) = readFallbackProviders(fallbackPatch(original, keepArrayShape = true))
        assertEquals(original, roundTrip)
        assertTrue(keepArray)
    }

    @Test
    fun `label 无 provider 时只显示模型`() {
        assertEquals("openai / gpt-5", FallbackEntry("openai", "gpt-5").label)
        assertEquals("gpt-5", FallbackEntry("", "gpt-5").label)
    }
}
