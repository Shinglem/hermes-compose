package dev.shinglem.hermes.feature.composer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CompletionPopoverTest {
    private val completion = CompletionState(
        trigger = CompletionTrigger.SLASH,
        entries = listOf(
            CompletionEntry(text = "skills", display = "/skills", meta = "管理技能", kind = "command"),
            CompletionEntry(text = "reload-skills", display = "/reload-skills", meta = "重新扫描技能"),
        ),
        selected = 0,
    )

    @Test
    fun `列出补全项与说明`() = runComposeUiTest {
        setContent { HermesTheme { CompletionPopover(completion = completion, onPick = {}) } }

        onNodeWithText("/skills").assertIsDisplayed()
        onNodeWithText("管理技能").assertIsDisplayed()
        onNodeWithText("/reload-skills").assertIsDisplayed()
    }

    @Test
    fun `点击某一项回调对应下标`() = runComposeUiTest {
        var picked = -1
        setContent {
            HermesTheme { CompletionPopover(completion = completion, onPick = { picked = it }) }
        }

        onNodeWithText("/reload-skills").performClick()
        assertEquals(1, picked)
    }
}
