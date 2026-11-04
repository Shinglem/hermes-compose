package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class StreamSegmentsTest {
    private fun tool(id: String, result: String? = null) = ChatItem.Tool(
        id = id,
        timestamp = null,
        name = "terminal",
        label = null,
        arguments = "{}",
        result = result,
        failed = false,
        pairedWithCall = true,
    )

    private fun StreamingState.types(): List<String> = segments.map {
        when (it) {
            is StreamSegment.Reasoning -> "reasoning"
            is StreamSegment.Answer -> "answer"
            is StreamSegment.Tool -> "tool"
        }
    }

    @Test
    fun `思考 工具 正文 交替到达时保持顺序`() {
        val state = StreamingState()
            .appendReasoning("想A")
            .startTool(tool("live:t1"))
            .finishTool("live:t1", "done", false)
            .appendAnswer("回复A")
            .appendReasoning("想B")
            .startTool(tool("live:t2"))
            .appendAnswer("回复B")

        assertEquals(
            listOf("reasoning", "tool", "answer", "reasoning", "tool", "answer"),
            state.types(),
        )
        assertEquals("想A", (state.segments[0] as StreamSegment.Reasoning).text)
        assertEquals("回复A", (state.segments[2] as StreamSegment.Answer).text)
        assertEquals("回复B", (state.segments.last() as StreamSegment.Answer).text)
    }

    @Test
    fun `同一段正文的增量合并进一段`() {
        val state = StreamingState().appendAnswer("半句").appendAnswer("话")
        assertEquals(1, state.segments.size)
        assertEquals("半句话", (state.segments.single() as StreamSegment.Answer).text)
    }

    @Test
    fun `interim 封段后新的增量另起一段`() {
        val state = StreamingState()
            .appendAnswer("第一段旁白")
            .sealInterim("第一段旁白")
            .appendAnswer("第二段正文")

        assertEquals(2, state.segments.size)
        assertTrue((state.segments[0] as StreamSegment.Answer).sealed)
        assertEquals("第二段正文", (state.segments[1] as StreamSegment.Answer).text)
    }

    @Test
    fun `interim 用后端文本校正已流出的段落`() {
        val state = StreamingState()
            .appendAnswer("第一段旁")
            .sealInterim("第一段旁白")

        assertEquals("第一段旁白", (state.segments.single() as StreamSegment.Answer).text)
    }

    @Test
    fun `message_complete 只替换尾部未封段的正文`() {
        val segments = StreamingState()
            .appendAnswer("旁白")
            .sealInterim("旁白")
            .startTool(tool("live:t1"))
            .appendAnswer("临时正文")
            .segments
            .withFinalText("最终正文")

        assertEquals(3, segments.size)
        assertEquals("旁白", (segments[0] as StreamSegment.Answer).text)
        assertEquals("最终正文", (segments[2] as StreamSegment.Answer).text)
    }

    @Test
    fun `message_complete 的文本已在前面出现过时不重复插入`() {
        val segments = StreamingState()
            .appendAnswer("最终正文")
            .sealInterim("最终正文")
            .segments
            .withFinalText("最终正文")

        assertEquals(1, segments.size)
    }

    @Test
    fun `结束一轮按段落顺序落地成条目`() {
        var seq = 0
        val items = StreamingState()
            .appendReasoning("想")
            .startTool(tool("live:t1", "done"))
            .appendAnswer("正文")
            .segments
            .toChatItems(Instant.fromEpochSeconds(1)) { kind -> "$kind-${seq++}" }

        assertEquals(3, items.size)
        assertIs<ChatItem.Reasoning>(items[0])
        assertIs<ChatItem.Tool>(items[1])
        assertIs<ChatItem.AssistantMessage>(items[2])
    }
}
