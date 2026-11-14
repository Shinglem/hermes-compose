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
 * S8 · 流式变换的唯一实现（[reduceStreamEvent]）。
 *
 * 迁移前前台（`ChatViewModel`）与后台影子（`SessionShadow`）各有一份平行实现，
 * 两份漂移过一次：前台会在"本轮没返回任何内容"时补一条提示，影子不补，
 * 于是切走再切回时那条提示就消失了。这里把合并后的语义逐条钉住。
 */
class ChatReducerTest {

    private val now = Instant.fromEpochSeconds(1_790_000_000)

    private fun state() = ChatState(key = SessionKey(null, "a"), load = Loadable.Ready(emptyList()))

    private fun apply(state: ChatState, vararg events: ChatStreamEvent): ChatState {
        var current = state
        var seq = 0
        events.forEach { event ->
            current = current.reduceStreamEvent(event, nextId = { kind -> "$kind#${seq++}" }, now = now)
        }
        return current
    }

    @Test
    fun `增量累积进同一条回答段`() {
        val applied = apply(state(), ChatStreamEvent.TurnStarted, ChatStreamEvent.Delta("你"), ChatStreamEvent.Delta("好"))
        assertEquals("你好", assertNotNull(applied.streaming).segments.filterIsInstance<StreamSegment.Answer>().single().text)
    }

    @Test
    fun `空增量不改变状态`() {
        val before = apply(state(), ChatStreamEvent.TurnStarted)
        assertEquals(before, apply(before, ChatStreamEvent.Delta("")))
    }

    /** 无内容事件（用量 / 标题）不该碰 `streaming`，也不该凭空造状态。 */
    @Test
    fun `无关事件保持状态不变`() {
        val before = state()
        assertEquals(before, apply(before, ChatStreamEvent.UsageUpdated(42)))
        assertEquals(before, apply(before, ChatStreamEvent.TitleChanged("x")))
        assertEquals(before, apply(before, ChatStreamEvent.Ignored))
    }

    @Test
    fun `工具段按 id 配对并保留结果`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.ToolStarted("t1", "terminal", "Running command · ls", "{}"),
            ChatStreamEvent.ToolCompleted("t1", "terminal", "a.txt", false),
        )
        val tools = assertNotNull(applied.streaming).segments.filterIsInstance<StreamSegment.Tool>()
        assertEquals(1, tools.size, "同一个 toolId 必须落成一段，不能重复")
        assertEquals("a.txt", tools.single().item.result)
        assertEquals(false, tools.single().item.failed)
        assertEquals(false, tools.single().item.running)
    }

    @Test
    fun `一轮结束把流式段落固化成条目`() {
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

    /**
     * 本地跑过这一轮但什么都没返回 → 必须留一条提示（#14：否则界面上「发了消息、什么都没发生」）。
     */
    @Test
    fun `本地跑过一轮但没内容时补提示`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.Completed(text = "", status = "ok", failed = false, error = null),
        )
        assertNull(applied.streaming)
        assertTrue(
            applied.items.single() is ChatItem.Notice,
            "空返回必须在对话里留痕，否则用户看不到任何反馈",
        )
    }

    /**
     * 本地**没跑过**这一轮却收到 complete（重连后第一条 / 快照已是终点）→ 不该凭空多出条目。
     * 这条是两个平行实现漂移的分歧点，合并后统一成"不补"。
     */
    @Test
    fun `没有本地流式时收到完成不多加条目`() {
        val applied = apply(
            state(),
            ChatStreamEvent.Completed(text = "", status = "ok", failed = false, error = null),
        )
        assertTrue(applied.items.isEmpty(), "没有本地流式过程时，complete 不该凭空产生提示条目")
        assertNull(applied.streaming)
    }

    @Test
    fun `失败的一轮留痕并置 sendError`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.Completed(text = "boom", status = "error", failed = true, error = "boom"),
        )
        val notice = applied.items.filterIsInstance<ChatItem.Notice>().single()
        assertTrue(notice.text.contains("本轮失败"), notice.text)
        assertNotNull(applied.sendError)
    }

    @Test
    fun `已完成的内容不会被再次追加`() {
        val applied = apply(
            state(),
            ChatStreamEvent.TurnStarted,
            ChatStreamEvent.Delta("全文"),
            ChatStreamEvent.Completed(text = "全文", status = "ok", failed = false, error = null),
        )
        val answers = applied.items.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals("全文", answers.single().markdown, "withFinalText 不该把同一段拼两次")
        assertEquals(1, applied.load.valueOrNull?.size)
    }
}
