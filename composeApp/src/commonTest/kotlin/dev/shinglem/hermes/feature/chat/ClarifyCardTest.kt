package dev.shinglem.hermes.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `待修复问题.md` 第 4 条：`clarify` 必须能答。
 *
 * 断言的是**提交给后端的 payload 形状**（`qid → 答案`），因为后端 `clarify_tool` 就按这个解析：
 * 单选给选项原文、多选给逗号分隔、自由输入优先、没答的题给空串。
 */
@OptIn(ExperimentalTestApi::class)
class ClarifyCardTest {

    private fun card(
        clarify: PendingClarify,
        onAnswer: (Map<String, String>) -> Unit = {},
        onCancel: () -> Unit = {},
    ): @Composable () -> Unit = {
        HermesTheme { ClarifyCard(clarify = clarify, onAnswer = onAnswer, onCancel = onCancel) }
    }

    private fun pending(vararg questions: ClarifyQuestion) = PendingClarify(
        requestId = "req-1",
        frameId = "srq-1",
        questions = questions.toList(),
    )

    @Test
    fun `title and question are rendered`() = runComposeUiTest {
        setContent(card(pending(ClarifyQuestion("q1", "要选哪个？", listOf("甲", "乙")))))
        onNodeWithText("需要你回答").assertIsDisplayed()
        onNodeWithText("要选哪个？").assertIsDisplayed()
        onNodeWithText("甲").assertIsDisplayed()
        onNodeWithText("乙").assertIsDisplayed()
    }

    @Test
    fun `single choice submits the picked option`() = runComposeUiTest {
        var answers: Map<String, String>? = null
        setContent(card(pending(ClarifyQuestion("q1", "要选哪个？", listOf("甲", "乙"))), onAnswer = { answers = it }))
        onNodeWithText("乙").performClick()
        onNodeWithText("提交").performClick()
        assertEquals(mapOf("q1" to "乙"), answers)
    }

    @Test
    fun `multi select submits comma separated values`() = runComposeUiTest {
        var answers: Map<String, String>? = null
        val clarify = pending(ClarifyQuestion("q1", "选哪些？", listOf("甲", "乙", "丙"), multiSelect = true))
        setContent(card(clarify, onAnswer = { answers = it }))
        onNodeWithText("选哪些？").assertIsDisplayed()
        onNodeWithText("甲").performClick()
        onNodeWithText("丙").performClick()
        onNodeWithText("提交").performClick()
        assertEquals(mapOf("q1" to "甲, 丙"), answers)
    }

    @Test
    fun `free text wins over the picked option`() = runComposeUiTest {
        var answers: Map<String, String>? = null
        setContent(card(pending(ClarifyQuestion("q1", "要选哪个？", listOf("甲", "乙"))), onAnswer = { answers = it }))
        onNodeWithText("甲").performClick()
        onNodeWithText("其他（直接输入）").performTextInput("都不对，用丁")
        onNodeWithText("提交").performClick()
        assertEquals(mapOf("q1" to "都不对，用丁"), answers)
    }

    @Test
    fun `batch submits every question keyed by qid`() = runComposeUiTest {
        var answers: Map<String, String>? = null
        val clarify = pending(
            ClarifyQuestion("q1", "第一个问题", listOf("甲", "乙")),
            ClarifyQuestion("q2", "第二个问题", listOf("丙", "丁")),
        )
        setContent(card(clarify, onAnswer = { answers = it }))
        onNodeWithText("乙").performClick()
        onNodeWithText("丁").performClick()
        onNodeWithText("提交").performClick()
        assertEquals(mapOf("q1" to "乙", "q2" to "丁"), answers)
    }

    /** 空选项 = 纯输入框（后端 `choices` 缺省时的形态）。 */
    @Test
    fun `question without choices is free text only`() = runComposeUiTest {
        var answers: Map<String, String>? = null
        setContent(card(pending(ClarifyQuestion("q1", "叫什么名字？")), onAnswer = { answers = it }))
        onNodeWithText("其他（直接输入）").performTextInput("hermes")
        onNodeWithText("提交").performClick()
        assertEquals(mapOf("q1" to "hermes"), answers)
    }

    @Test
    fun `cancel does not submit answers`() = runComposeUiTest {
        var cancelled = false
        var answers: Map<String, String>? = null
        setContent(
            card(pending(ClarifyQuestion("q1", "要选哪个？", listOf("甲"))), onAnswer = { answers = it }) {
                cancelled = true
            },
        )
        onNodeWithText("取消").performClick()
        assertTrue(cancelled)
        assertEquals(null, answers)
    }
}
