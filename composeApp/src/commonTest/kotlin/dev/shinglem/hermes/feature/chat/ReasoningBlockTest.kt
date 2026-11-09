package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReasoningBlockTest {

    private val longText = "这一段足够长，用来把思考块撑过限高。\n\n".repeat(30)

    @Test
    fun `流式思考块跟随最新内容`() = runComposeUiTest {
        var text by mutableStateOf(longText)
        setContent {
            HermesTheme {
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    ReasoningBlock(text = text, streaming = true)
                }
            }
        }
        waitForIdle()

        // 首帧布局给出 maxValue 之后才会补滚，多推几帧。
        var frames = 0
        while (frames < 120 && (scrollValue() ?: 0f) <= 0f) {
            mainClock.advanceTimeByFrame()
            frames++
        }
        val start = scrollValue() ?: -1f
        assertTrue(start > 0f, "首次布局后就该贴底，实际 start=$start max=${scrollMax()}")

        text += "后续又想了很久的一段。\n\n".repeat(10)
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { (scrollValue() ?: 0f) > start }

        val end = scrollValue() ?: error("思考块应有可滚动内容")
        val max = scrollMax() ?: error("思考块应有可滚动内容")
        assertTrue(end > start, "内容增长后应继续跟随，start=$start end=$end")
        assertEquals(max, end, 1f, "跟随结束时应贴底")
    }

    @Test
    fun `历史思考块停在开头`() = runComposeUiTest {
        setContent {
            HermesTheme {
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    ReasoningBlock(text = longText, streaming = false)
                }
            }
        }
        waitForIdle()
        assertEquals(0f, scrollValue() ?: error("思考块应有可滚动内容"), 1f)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.scrollAxisRange() =
        onAllNodes(hasScrollAction()).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)

    private fun androidx.compose.ui.test.ComposeUiTest.scrollValue(): Float? =
        scrollAxisRange()?.value?.invoke()

    private fun androidx.compose.ui.test.ComposeUiTest.scrollMax(): Float? =
        scrollAxisRange()?.maxValue?.invoke()
}
