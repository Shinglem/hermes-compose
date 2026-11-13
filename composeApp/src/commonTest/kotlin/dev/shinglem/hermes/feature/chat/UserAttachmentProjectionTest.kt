package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #20：用户消息里的附件必须可见。
 *
 * 后端把附件写成正文里的 `@image:<绝对路径>` 行 + `[screenshot]` 占位行；
 * 直接渲染等于让用户看到一串没意义的路径。这里验证拆分与投影都对。
 */
class UserAttachmentProjectionTest {

    @Test
    fun `从正文里拆出 image 引用并丢掉占位行`() {
        val raw = """
            帮我看看这张图
            @image:C:\ai\hermes\data\cache\images\upload_abc.png
            [screenshot]
        """.trimIndent()
        val (text, attachments) = splitUserAttachments(raw)
        assertEquals("帮我看看这张图", text)
        assertEquals(1, attachments.size)
        assertEquals("""C:\ai\hermes\data\cache\images\upload_abc.png""", attachments[0].path)
        assertEquals("upload_abc.png", attachments[0].name)
    }

    @Test
    fun `没有附件的正文原样保留`() {
        val raw = "普通消息，里面有个 @ 符号也不算引用\n第二行"
        val (text, attachments) = splitUserAttachments(raw)
        assertEquals(raw, text)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun `以 @ 开头但不是 image 引用的行不受影响`() {
        val raw = "@file:src/main.kt\n请解释这个文件"
        val (text, attachments) = splitUserAttachments(raw)
        assertEquals(raw, text)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun `多张图按顺序全部拆出且去重`() {
        val raw = """
            两张图
            @image:/tmp/a.png
            [screenshot]
            @image:/tmp/b.jpg
            [screenshot]
            @image:/tmp/a.png
        """.trimIndent()
        val (text, attachments) = splitUserAttachments(raw)
        assertEquals("两张图", text)
        assertEquals(listOf("/tmp/a.png", "/tmp/b.jpg"), attachments.map { it.path })
        assertEquals(listOf("a.png", "b.jpg"), attachments.map { it.name })
    }

    @Test
    fun `投影时 user 行带出附件`() {
        val rows = listOf(
            ChatRow(
                id = "u1",
                role = ROLE_USER,
                text = "看这个\n@image:C:\\x\\y.png\n[screenshot]",
                timestamp = null,
                toolName = null,
                toolCallId = null,
                toolCalls = emptyList(),
                labels = emptyMap(),
                displayKind = null,
                hidden = false,
            ),
        )
        val items = projectChat(rows)
        assertEquals(1, items.size)
        val user = items[0] as ChatItem.UserMessage
        assertEquals("看这个", user.text)
        assertEquals(1, user.attachments.size)
        assertEquals("y.png", user.attachments[0].name)
    }

    @Test
    fun `只有附件没有文字的消息也要成条`() {
        // 用户只发图不发字是完全合法的；若按 isNotBlank 过滤就整条消失。
        val rows = listOf(
            ChatRow(
                id = "u2",
                role = ROLE_USER,
                text = "@image:C:\\x\\only.png\n[screenshot]",
                timestamp = null,
                toolName = null,
                toolCallId = null,
                toolCalls = emptyList(),
                labels = emptyMap(),
                displayKind = null,
                hidden = false,
            ),
        )
        val items = projectChat(rows)
        assertEquals(1, items.size)
        val user = items[0] as ChatItem.UserMessage
        assertEquals("", user.text)
        assertEquals("only.png", user.attachments[0].name)
    }

    @Test
    fun `空引用被丢弃`() {
        val (_, attachments) = splitUserAttachments("@image:   \n[screenshot]")
        assertTrue(attachments.isEmpty())
    }
}
