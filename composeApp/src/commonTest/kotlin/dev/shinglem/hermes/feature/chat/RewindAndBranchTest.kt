package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.contract.ToolLabelDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `#19` 的「对话-重发」与「对话-分支」两行（S8 从 `ChatViewModel` 抽出的判据）。
 *
 * 两处都容易算错，而且**错法很隐蔽**：
 *
 * - 重发算错「截断后是否为空」→ 确认框少一句「这会清空当前对话」，用户点下去才发现全没了；
 * - 分支拿 UI 列表下标当 `count` → 后端保留的历史条数不对，工具卡片 / 思考块会被算进去。
 */
class RewindAndBranchTest {

    private val now = Instant.fromEpochSeconds(1_790_000_000)

    private fun user(id: String, text: String = "u$id") = ChatItem.UserMessage(id, now, text, emptyList())
    private fun assistant(id: String) = ChatItem.AssistantMessage(id, now, "a$id")

    private fun row(id: String, role: String, text: String = "t$id") = ChatRow(
        id = id,
        role = role,
        text = text,
        timestamp = null,
        toolName = null,
        toolCallId = null,
        toolCalls = emptyList(),
        labels = emptyMap<String, List<ToolLabelDto>>(),
        displayKind = null,
        hidden = false,
    )

    // ── 重发 ────────────────────────────────────────────────────────────

    @Test
    fun `目标是第一条 user 消息时标记会清空历史`() {
        val items = listOf(user("1"), assistant("2"))
        val target = rewindTarget(items, "1", "原文")
        assertEquals("1", target?.messageId)
        assertEquals("原文", target?.text)
        assertTrue(target?.emptyAfterTruncate == true, "前面没有 user 消息，截断后历史会空 —— 确认框必须警告")
    }

    @Test
    fun `目标前面还有 user 消息时不标记清空`() {
        val items = listOf(user("1"), assistant("2"), user("3"), assistant("4"))
        val target = rewindTarget(items, "3", "第二次提问")
        assertFalse(target?.emptyAfterTruncate == true)
    }

    @Test
    fun `目标不在列表里返回 null`() {
        assertNull(rewindTarget(listOf(user("1")), "nope", "x"))
    }

    /** 工具卡片 / 通知条目不算 user 消息，不该影响"前面还有几条 user 提问"的判断。 */
    @Test
    fun `非对话条目不参与 user 计数`() {
        val items = listOf(
            ChatItem.Notice("n1", now, "提示"),
            user("1"),
            ChatItem.Tool(
                id = "t1", timestamp = now, name = "terminal", label = null, arguments = "{}",
                result = "ok", failed = false, pairedWithCall = true, running = false,
            ),
            user("2"),
        )
        val target = rewindTarget(items, "2", "第二条")
        assertFalse(target?.emptyAfterTruncate == true, "前面已经有一条 user 消息，不该判成清空")
    }

    // ── 分支 ────────────────────────────────────────────────────────────

    @Test
    fun `分支 count 按可见历史序号算`() {
        val rows = listOf(
            row("1", ROLE_USER),
            row("2", ROLE_ASSISTANT),
            row("3", ROLE_USER),
        )
        assertEquals(1, branchTarget(rows, "1", "")?.count)
        assertEquals(2, branchTarget(rows, "2", "")?.count)
        assertEquals(3, branchTarget(rows, "3", "")?.count)
    }

    /**
     * **关键判据**：tool 行 / 空文本 assistant 行 / hidden 行都不计入可见历史。
     * 算错的话后端保留的条数与界面显示不一致。
     */
    @Test
    fun `工具行与空文本行不占序号`() {
        val rows = listOf(
            row("1", ROLE_USER),
            row("2", "tool"),
            row("3", ROLE_ASSISTANT, text = ""),
            row("4", ROLE_ASSISTANT),
        )
        assertEquals(1, branchTarget(rows, "1", "")?.count)
        assertEquals(2, branchTarget(rows, "4", "")?.count, "中间两条不可见，答案应为第 2 条")
        assertNull(branchTarget(rows, "2", ""), "tool 行不是可分支目标")
        assertNull(branchTarget(rows, "3", ""), "空 assistant 行不是可分支目标")
    }

    @Test
    fun `标题为空时用兜底`() {
        val rows = listOf(row("1", ROLE_USER))
        assertEquals("分支", branchTarget(rows, "1", "")?.title)
        assertEquals("自定义", branchTarget(rows, "1", "自定义")?.title)
        assertEquals("自定义", branchTarget(rows, "1", "自定义", fallbackTitle = "兜底")?.title)
    }

    @Test
    fun `目标不在可见历史里返回 null`() {
        assertNull(branchTarget(listOf(row("1", ROLE_USER)), "nope", ""))
    }

    @Test
    fun `isVisibleHistoryRow 只认 user 与 assistant 且有文本`() {
        assertTrue(isVisibleHistoryRow(row("1", ROLE_USER)))
        assertTrue(isVisibleHistoryRow(row("2", ROLE_ASSISTANT, "有内容")))
        assertFalse(isVisibleHistoryRow(row("3", ROLE_ASSISTANT, "")), "空 assistant 行不算")
        assertFalse(isVisibleHistoryRow(row("4", "tool")))
        assertFalse(isVisibleHistoryRow(row("5", "system")))
    }
}
