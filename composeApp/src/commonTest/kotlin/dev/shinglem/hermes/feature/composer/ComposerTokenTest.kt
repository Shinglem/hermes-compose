package dev.shinglem.hermes.feature.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerTokenTest {
    @Test
    fun `行首的 @ 触发引用补全`() {
        val token = activeToken("@", 1)
        assertEquals("@", token?.text)
        assertEquals(0, token?.start)
        assertEquals(CompletionTrigger.REFERENCE, token?.trigger)
    }

    @Test
    fun `空白后的 @ 触发，词内不含空白`() {
        val text = "看下 @composeApp"
        val token = activeToken(text, text.length)
        assertEquals("@composeApp", token?.text)
        assertEquals(3, token?.start)
        assertEquals(null, activeToken("看下 @composeApp 然后", text.length + 3))
    }

    @Test
    fun `邮箱不触发引用补全`() {
        val text = "mail a@b"
        assertNull(activeToken(text, text.length))
    }

    @Test
    fun `行首斜杠触发命令补全，行内斜杠不触发`() {
        val command = activeToken("/sk", 3)
        assertEquals(CompletionTrigger.SLASH, command?.trigger)
        assertEquals("/sk", command?.text)
        assertNull(activeToken("a /sk", 5))
    }

    @Test
    fun `接受引用补全后补空格并把光标放到末尾`() {
        val value = TextFieldValue("@co", selection = TextRange(3))
        val completion = CompletionState(
            trigger = CompletionTrigger.REFERENCE,
            entries = listOf(CompletionEntry(text = "@file:composeApp/build.gradle.kts", display = "build.gradle.kts")),
            start = 0,
            end = 3,
        )
        val accepted = completion.accepted(value)
        assertEquals("@file:composeApp/build.gradle.kts ", accepted?.text)
        assertEquals(accepted?.text?.length, accepted?.selection?.end)
    }

    @Test
    fun `接受命令补全时补斜杠`() {
        val value = TextFieldValue("/sk", selection = TextRange(3))
        val completion = CompletionState(
            trigger = CompletionTrigger.SLASH,
            entries = listOf(CompletionEntry(text = "skills", display = "/skills")),
            start = 0,
            end = 3,
        )
        assertEquals("/skills", completion.accepted(value)?.text)
    }

    @Test
    fun `补全返回前文本继续增长时以当前光标为替换终点`() {
        // 请求发出时光标在 3（`/sk`），返回前已输入到 6（`/skill`）。
        val value = TextFieldValue("/skill", selection = TextRange(6))
        val completion = CompletionState(
            trigger = CompletionTrigger.SLASH,
            entries = listOf(CompletionEntry(text = "skin", display = "/skin")),
            start = 0,
            end = 3,
        )
        val accepted = completion.accepted(value)
        assertEquals("/skin", accepted?.text)
        assertEquals(5, accepted?.selection?.end)
    }

    @Test
    fun `上下移动选中项会循环`() {
        val completion = CompletionState(
            trigger = CompletionTrigger.REFERENCE,
            entries = List(3) { CompletionEntry(text = "@$it", display = "@$it") },
            selected = 0,
        )
        assertEquals(1, completion.moved(1).selected)
        assertEquals(2, completion.moved(-1).selected)
    }

    @Test
    fun `大段粘贴与路径文本的判定`() {
        assertTrue(isLargePaste("\n".repeat(40)))
        assertTrue(isLargePaste("x".repeat(2_000)))
        assertFalse(isLargePaste("hello"))

        assertTrue(looksLikePath("D:\\work\\repo"))
        assertFalse(looksLikePath("hello world"))
        assertFalse(looksLikePath("a/b c"))
    }

    @Test
    fun `引用块逐行加前缀并丢掉空行`() {
        assertEquals("> 第一行\n> 第二行", quoteBlock("第一行\n\n第二行\n"))
    }

    @Test
    fun `引用块截断超长行与超出行数`() {
        val longLine = "x".repeat(QUOTE_LINE_LIMIT + 10)
        val quoted = quoteBlock(longLine)
        assertEquals(QUOTE_LINE_LIMIT + 2 + 1, quoted.length) // "> " + 500 字符 + 省略号
        assertTrue(quoted.endsWith("…"))

        val manyLines = (1..QUOTE_MAX_LINES + 5).joinToString("\n") { "line$it" }
        assertEquals(QUOTE_MAX_LINES, quoteBlock(manyLines).lines().size)
    }

    @Test
    fun `空白文本不产出引用块`() {
        assertEquals("", quoteBlock("   \n\n  "))
    }
}
