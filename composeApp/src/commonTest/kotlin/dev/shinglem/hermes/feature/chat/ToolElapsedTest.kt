package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 工具执行时长显示（#18）：长命令在跑时界面必须有可见变化。 */
class ToolElapsedTest {
    @Test
    fun `不足一分钟只显示分与两位秒`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:05", formatElapsed(5))
        assertEquals("0:59", formatElapsed(59))
        assertEquals("1:00", formatElapsed(60))
        assertEquals("3:42", formatElapsed(222))
        assertEquals("9:59", formatElapsed(599))
    }

    @Test
    fun `超过一小时带小时段`() {
        assertEquals("1:00:00", formatElapsed(3_600))
        assertEquals("1:02:03", formatElapsed(3_723))
    }

    @Test
    fun `负数按零处理`() {
        assertEquals("0:00", formatElapsed(-5))
    }

    @Test
    fun `提示阈值是 60 秒`() {
        assertEquals(60L, LONG_TOOL_HINT_SECONDS)
    }

    @Test
    fun `tool_complete 之后不再标记执行中`() {
        val started = ChatItem.Tool(
            id = "call:1",
            timestamp = Instant.fromEpochMilliseconds(0),
            name = "terminal",
            label = null,
            arguments = "{}",
            result = null,
            failed = false,
            pairedWithCall = true,
            running = true,
        )
        val streaming = StreamingState(segments = listOf(StreamSegment.Tool(started)))
        assertTrue(assertIsTool(streaming).running)

        val finished = streaming.finishTool("call:1", result = "ok", failed = false)
        val done = assertIsTool(finished)
        assertFalse(done.running)
        assertEquals("ok", done.result)
    }

    @Test
    fun `一轮结束后未完成的工具也不再显示执行中`() {
        val stuck = ChatItem.Tool(
            id = "call:1",
            timestamp = null,
            name = "terminal",
            label = null,
            arguments = "{}",
            result = null,
            failed = false,
            pairedWithCall = true,
            running = true,
        )
        val items = listOf(StreamSegment.Tool(stuck)).toChatItems(Instant.fromEpochMilliseconds(0)) {
            "x"
        }
        assertFalse((items.single() as ChatItem.Tool).running)
    }

    private fun assertIsTool(streaming: StreamingState): ChatItem.Tool =
        (streaming.segments.single() as StreamSegment.Tool).item
}
