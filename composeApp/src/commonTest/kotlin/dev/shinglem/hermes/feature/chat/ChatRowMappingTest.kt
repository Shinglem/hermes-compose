package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.contract.TranscriptRowDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `TranscriptRowDto` → `ChatRow` 的字段映射（重点：reasoning 的取值优先级）。 */
class ChatRowMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun rowOf(raw: String): ChatRow = json.decodeFromString<TranscriptRowDto>(raw).toRow()

    @Test
    fun `reasoning_content 优先于 reasoning`() {
        val row = rowOf(
            """{"id":"a1","role":"assistant","content":"正文","reasoning":"旧字段","reasoning_content":"新字段"}""",
        )
        assertEquals("新字段", row.reasoning)
    }

    @Test
    fun `只有 reasoning 时取 reasoning`() {
        val row = rowOf("""{"id":"a1","role":"assistant","content":"","reasoning":"思考"}""")
        assertEquals("思考", row.reasoning)
    }

    @Test
    fun `reasoning_content 为空白时回落到 reasoning`() {
        val row = rowOf(
            """{"id":"a1","role":"assistant","content":"","reasoning":"思考","reasoning_content":"   "}""",
        )
        assertEquals("思考", row.reasoning)
    }

    @Test
    fun `两者都没有时为 null`() {
        assertNull(rowOf("""{"id":"a1","role":"assistant","content":"正文"}""").reasoning)
    }

    @Test
    fun `tool 行即使带了 reasoning 也不会渲染成思考块`() {
        val row = rowOf(
            """{"id":"t1","role":"tool","content":"结果","tool_call_id":"call-1","reasoning":"不该显示"}""",
        )
        // 映射层不做角色判断，投影层才决定要不要渲染。
        assertEquals("不该显示", row.reasoning)
        assertTrue(projectChat(listOf(row)).none { it is ChatItem.Reasoning })
    }
}
