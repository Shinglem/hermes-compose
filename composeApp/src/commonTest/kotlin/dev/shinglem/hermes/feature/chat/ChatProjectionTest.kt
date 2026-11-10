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
        reasoning: String? = null,
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
        reasoning = reasoning,
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

    @Test
    fun `reasoning 按步分段且顺序为 思考 正文 工具`() {
        val rows = listOf(
            row("u1", "user", "很多 VPN 订阅，给个方案"),
            row(
                id = "a1",
                role = "assistant",
                text = "我先查看既有约定。",
                reasoning = "用户问 VPN 订阅，需要给方案推荐。先理解需求。",
                toolCalls = listOf(ChatToolCall("call-1", "skill_view", "{}")),
            ),
            row("t1", "tool", "约定内容", toolName = "skill_view", toolCallId = "call-1"),
            // 这一步只有思考 + 工具，没有正文（实测 rid=8218 就是这个形状）
            row(
                id = "a2",
                role = "assistant",
                text = "",
                reasoning = "再确认端口占用。",
                toolCalls = listOf(ChatToolCall("call-2", "terminal", "{}")),
            ),
            row("t2", "tool", "ok", toolName = "terminal", toolCallId = "call-2"),
            row("a3", "assistant", "结论如下。", reasoning = "汇总。"),
        )

        val items = projectChat(rows)

        assertEquals(
            listOf(
                "u1",
                "reason:a1", "a1", "call:call-1",
                "reason:a2", "call:call-2",
                "reason:a3", "a3",
            ),
            items.map { it.id },
        )
        assertEquals(
            "用户问 VPN 订阅，需要给方案推荐。先理解需求。",
            assertIs<ChatItem.Reasoning>(items[1]).text,
        )
    }

    @Test
    fun `没有 reasoning 的 assistant 行不产出思考块`() {
        val rows = listOf(
            row("a1", "assistant", "正文", reasoning = null),
            row("a2", "assistant", "正文2", reasoning = ""),
            row("a3", "assistant", "正文3", reasoning = "   "),
        )
        assertTrue(projectChat(rows).none { it is ChatItem.Reasoning })
    }

    @Test
    fun `content 为空的 assistant 行不产出空正文气泡`() {
        val rows = listOf(
            row(
                id = "a1",
                role = "assistant",
                text = "",
                reasoning = "只有思考",
                toolCalls = listOf(ChatToolCall("call-1", "terminal", "{}")),
            ),
        )
        val items = projectChat(rows)
        assertEquals(listOf("reason:a1", "call:call-1"), items.map { it.id })
        assertTrue(items.none { it is ChatItem.AssistantMessage })
    }
}
