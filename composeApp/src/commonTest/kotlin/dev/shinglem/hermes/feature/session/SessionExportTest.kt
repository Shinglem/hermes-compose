package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.feature.chat.ChatItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionExportTest {
    private val items = listOf(
        ChatItem.UserMessage("u1", null, "你好"),
        ChatItem.AssistantMessage("a1", null, "**在**"),
        ChatItem.Tool(
            id = "call:1",
            timestamp = null,
            name = "shell",
            label = null,
            arguments = "{\"cmd\":\"ls\"}",
            result = "a.txt",
            failed = false,
            pairedWithCall = true,
        ),
    )

    @Test
    fun `file name is sanitized and keeps the first 8 id chars`() {
        assertEquals(
            "session-abcdef12.json",
            SessionExport.jsonFileName("中文标题", "abcdef1234567890"),
        )
    }

    @Test
    fun `file name keeps ascii title`() {
        assertEquals("my-chat-abcdef12.md", SessionExport.markdownFileName(" My Chat ", "abcdef1234567890"))
    }

    @Test
    fun `json carries the export contract fields`() {
        val json = SessionExport.toJson(
            sessionId = "abc",
            title = "T",
            items = items,
            exportedAt = Instant.fromEpochSeconds(1_700_000_000),
        )
        assertTrue(json.contains("\"session_id\": \"abc\""))
        assertTrue(json.contains("\"message_count\": 3"))
        assertTrue(json.contains("\"exported_at\""))
        assertTrue(json.contains("\"messages\""))
        // 工具的 arguments / result 都要在导出里（否则导出后无法复盘）。
        assertTrue(json.contains("a.txt"))
    }

    @Test
    fun `markdown renders roles and tool details`() {
        val markdown = SessionExport.toMarkdown(
            sessionId = "abc",
            title = "会话",
            items = items,
            exportedAt = Instant.fromEpochSeconds(1_700_000_000),
        )
        assertTrue(markdown.startsWith("# 会话"))
        assertTrue(markdown.contains("## 用户"))
        assertTrue(markdown.contains("## 助手"))
        assertTrue(markdown.contains("<details><summary>工具：shell</summary>"))
        assertTrue(markdown.contains("a.txt"))
    }
}
