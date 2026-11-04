package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 切走期间的"影子状态"：别的会话的增量必须继续累积在它的快照上，
 * 否则切回时只能看到切走前那一帧（`待修复问题.md` 第 3 条，用户实测复现过两次）。
 */
class SessionShadowTest {

    private val now = Instant.fromEpochSeconds(1_790_000_000)

    private fun state() = ChatState(key = SessionKey(null, "a"), load = Loadable.Ready(emptyList()))

    private fun apply(state: ChatState, vararg events: ChatStreamEvent): ChatState {
        var current = state
        var seq = 0
        events.forEach { event ->
            current = current.applyShadowEvent(event, nextId = { kind -> "$kind#${seq++}" }, now = now)
        }
        return current
    }

    @Test
    fun `deltas accumulate into the streaming answer`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.Delta("你"),
            ChatStreamEvent.Delta("好"),
        )
        val streaming = assertNotNull(applied.streaming)
        assertEquals("你好", streaming.segments.filterIsInstance<StreamSegment.Answer>().single().text)
    }

    @Test
    fun `reasoning keeps its own ordered segment`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.ReasoningDelta("想一下"),
            ChatStreamEvent.Delta("答"),
        )
        val segments = assertNotNull(applied.streaming).segments
        assertTrue(segments[0] is StreamSegment.Reasoning)
        assertTrue(segments[1] is StreamSegment.Answer)
    }

    @Test
    fun `tool start and completion update the same item`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.ToolStarted("t1", "terminal", "Running command · ls", "{}"),
            ChatStreamEvent.ToolCompleted("t1", "terminal", "a.txt", false),
        )
        val tools = assertNotNull(applied.streaming).segments.filterIsInstance<StreamSegment.Tool>()
        assertEquals(1, tools.size)
        assertEquals("a.txt", tools.single().item.result)
        assertEquals(false, tools.single().item.failed)
    }

    /** 切走期间整轮跑完：内容必须落成正式条目，`streaming` 清空。 */
    @Test
    fun `completed turn seals the answer into items`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.Delta("半截"),
            ChatStreamEvent.Delta("，补全了"),
            ChatStreamEvent.Completed(text = "半截，补全了", status = "ok", failed = false, error = null),
        )
        assertNull(applied.streaming)
        val items = applied.items
        assertEquals(1, items.size)
        assertEquals("半截，补全了", (items.single() as ChatItem.AssistantMessage).markdown)
    }

    @Test
    fun `events without content leave the state untouched`() {
        val before = state()
        assertEquals(before, apply(before, ChatStreamEvent.UsageUpdated(42)))
    }

    /** 已经不在流式时收到 `message.complete`（例如快照已是终点）：不该凭空多出条目。 */
    @Test
    fun `completed without streaming adds nothing`() {
        val applied = apply(state(), ChatStreamEvent.Completed(text = "", status = "ok", failed = false, error = null))
        assertTrue(applied.items.isEmpty())
    }

    @Test
    fun `existing history is preserved`() {
        val withHistory = ChatState(
            key = SessionKey(null, "a"),
            load = Loadable.Ready(listOf(ChatItem.UserMessage("u1", now, "旧消息"))),
        )
        val applied = apply(
            withHistory,
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.Delta("新回答"),
            ChatStreamEvent.Completed(text = "新回答", status = "ok", failed = false, error = null),
        )
        assertEquals(2, applied.items.size)
        assertEquals("旧消息", (applied.items.first() as ChatItem.UserMessage).text)
    }
}
