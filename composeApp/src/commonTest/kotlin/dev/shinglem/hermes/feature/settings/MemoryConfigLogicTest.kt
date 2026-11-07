package dev.shinglem.hermes.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 记忆页「内置层」四项配置的读写：读用 [readPath]，写用 [buildConfigPatch]（稀疏 diff）。
 *
 * 样本取自本机 `/api/config` 的 `memory` 段（默认 2200 / 1375）。
 */
class MemoryConfigLogicTest {

    private val config = Json.parseToJsonElement(
        """
        {"memory":{"memory_enabled":true,"user_profile_enabled":true,
                   "memory_char_limit":2200,"user_char_limit":1375,
                   "provider":""}}
        """.trimIndent(),
    ).jsonObject

    @Test
    fun readsAllFourFields() {
        assertEquals("true", config.readPath("memory.memory_enabled"))
        assertEquals("true", config.readPath("memory.user_profile_enabled"))
        assertEquals("2200", config.readPath("memory.memory_char_limit"))
        assertEquals("1375", config.readPath("memory.user_char_limit"))
    }

    @Test
    fun patchCarriesOnlyEditedFields() {
        val patch = buildConfigPatch(
            mapOf(
                "memory.memory_enabled" to "false",
                "memory.memory_char_limit" to "3000",
            ),
            MEMORY_FIELDS,
        )
        assertEquals("false", patch.readPath("memory.memory_enabled"))
        assertEquals("3000", patch.readPath("memory.memory_char_limit"))
        // 没改的两项不能出现在 patch 里：后端是 deep-merge，多写会把别处刚改的值盖回去
        assertEquals("", patch.readPath("memory.user_profile_enabled"))
        assertEquals("", patch.readPath("memory.user_char_limit"))
    }

    @Test
    fun invalidNumberIsDroppedNotSentAsString() {
        val patch = buildConfigPatch(
            mapOf("memory.memory_char_limit" to "abc"),
            MEMORY_FIELDS,
        )
        assertTrue(patch.isEmpty())
    }

    @Test
    fun everySpecTargetsMemorySection() {
        assertTrue(MEMORY_FIELDS.all { it.path.startsWith("memory.") })
        assertEquals(4, MEMORY_FIELDS.size)
    }
}
