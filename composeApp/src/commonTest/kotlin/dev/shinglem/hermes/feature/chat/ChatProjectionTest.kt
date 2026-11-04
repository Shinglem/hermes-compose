package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatProjectionTest {
    private fun row(
        id: String,
        role: String,
        text: String = "",
        toolName: String? = null,
        toolCallId: String? = null,
        toolCalls: List<ChatToolCall> = emptyList(),
        labels: Map<String, List<dev.shinglem.hermes.core.contract.ToolLabelDto>> = emptyMap(),
        displayKind: String? = null,
        finishReason: String? = null,
    ) = ChatRow(
        id = id,
        role = role,
        text = text,
        timestamp = null,
        toolName = toolName,
        toolCallId = toolCallId,
        toolCalls = toolCalls,
        labels = labels,
        displayKind = displayKind,
        hidden = displayKind == "hidden",
        finishReason = finishReason,
    )

    @Test
    fun `assistant 文本与工具调用各自成条`() {
        val rows = listOf(
            row("u1", "user", "帮我看看"),
            row(
                id = "a1",
                role = "assistant",
                text = "我先查一下。",
                toolCalls = listOf(ChatToolCall("call-1", "session_search", """{"q":"x"}""")),
            ),
            row("t1", "tool", "结果内容", toolName = "session_search", toolCallId = "call-1"),
        )

        val items = projectChat(rows)

        assertEquals(3, items.size)
        assertIs<ChatItem.UserMessage>(items[0])
        assertIs<ChatItem.AssistantMessage>(items[1])
        val tool = assertIs<ChatItem.Tool>(items[2])
        assertEquals("session_search", tool.name)
        assertEquals("结果内容", tool.result)
        assertTrue(tool.pairedWithCall)
        assertFalse(tool.failed)
    }

    @Test
    fun `已配对的 tool 行不会重复成卡片`() {
        val rows = listOf(
            row(
                id = "a1",
                role = "assistant",
                toolCalls = listOf(ChatToolCall("call-1", "terminal", "{}")),
            ),
            row("t1", "tool", "ok", toolName = "terminal", toolCallId = "call-1"),
        )
        val items = projectChat(rows)
        assertEquals(1, items.size)
        assertIs<ChatItem.Tool>(items.single())
    }

    @Test
    fun `找不到调用方的 tool 行单独成卡片`() {
        val rows = listOf(row("t9", "tool", "历史被截断后的结果", toolName = "read_file"))
        val tool = assertIs<ChatItem.Tool>(projectChat(rows).single())
        assertFalse(tool.pairedWithCall)
        assertEquals("read_file", tool.name)
    }

    @Test
    fun `hidden 行整条跳过`() {
        val rows = listOf(
            row("u1", "user", "可见", displayKind = null),
            row("u2", "user", "隐藏", displayKind = "hidden"),
        )
        assertEquals(listOf("u1"), projectChat(rows).map { it.id })
    }

    @Test
    fun `工具结果失败时被标记`() {
        val rows = listOf(
            row(
                id = "a1",
                role = "assistant",
                toolCalls = listOf(ChatToolCall("call-1", "terminal", "{}")),
            ),
            row("t1", "tool", "Error: command not found", toolCallId = "call-1"),
        )
        assertTrue(assertIs<ChatItem.Tool>(projectChat(rows).single()).failed)
    }

    @Test
    fun `工具标签优先于原始工具名`() {
        val rows = listOf(
            row(
                id = "a1",
                role = "assistant",
                toolCalls = listOf(ChatToolCall("call-1", "tool_search", "{}")),
                labels = mapOf(
                    "call-1" to listOf(
                        dev.shinglem.hermes.core.contract.ToolLabelDto(
                            text = "Searching tools · session search",
                            name = "tool_search",
                        ),
                    ),
                ),
            ),
        )
        val tool = assertIs<ChatItem.Tool>(projectChat(rows).single())
        assertEquals("Searching tools · session search", tool.label)
        assertEquals("tool_search", tool.name)
    }

    @Test
    fun `复制文本包含工具名 参数与结果`() {
        val tool = ChatItem.Tool(
            id = "call:1",
            timestamp = null,
            name = "terminal",
            label = null,
            arguments = "{\"cmd\":\"ls\"}",
            result = "a.txt",
            failed = false,
            pairedWithCall = true,
        )
        val text = tool.copyText()
        assertTrue("terminal" in text)
        assertTrue("ls" in text)
        assertTrue("a.txt" in text)
    }

    @Test
    fun `空 assistant 只留工具卡片`() {
        val rows = listOf(
            row(
                id = "a1",
                role = "assistant",
                text = "",
                toolCalls = listOf(ChatToolCall("call-1", "terminal", "{}")),
            ),
        )
        assertIs<ChatItem.Tool>(projectChat(rows).single())
    }

    @Test
    fun `finish_reason 异常时补一条提示`() {
        val rows = listOf(
            row("u1", "user", "写长文"),
            row("a1", "assistant", "开头一段…", finishReason = "length"),
        )

        val items = projectChat(rows)

        val notice = items.filterIsInstance<ChatItem.Notice>().single()
        assertEquals("notice:a1", notice.id)
        assertTrue(notice.text.contains("长度上限"))
    }

    @Test
    fun `正常结束不补提示`() {
        val rows = listOf(
            row("a1", "assistant", "完整回答", finishReason = "stop"),
            row("a2", "assistant", "带工具的回答", finishReason = "tool_calls"),
        )
        assertTrue(projectChat(rows).none { it is ChatItem.Notice })
    }

    @Test
    fun `空文本的异常 assistant 也要留提示`() {
        val rows = listOf(row("a1", "assistant", "", finishReason = "content_filter"))

        val items = projectChat(rows)

        assertEquals(1, items.size)
        assertIs<ChatItem.Notice>(items.single())
    }
}
