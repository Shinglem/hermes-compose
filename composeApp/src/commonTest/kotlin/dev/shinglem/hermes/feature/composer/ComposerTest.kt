package dev.shinglem.hermes.feature.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ComposerTest {
    @Composable
    private fun TestComposer(
        initial: ComposerUiState = ComposerUiState(),
        canSubmit: Boolean = true,
        busy: Boolean = false,
        onSend: (String) -> Unit = {},
        onAccept: (Int?) -> Boolean = { false },
        onDismiss: () -> Unit = {},
        onPickFiles: () -> Unit = {},
        onDropFiles: (List<String>) -> Unit = {},
    ) {
        var state by remember { mutableStateOf(initial) }
        Composer(
            controller = ComposerController(
                state = state,
                onValueChange = { state = state.copy(value = it) },
                onAcceptCompletion = onAccept,
                onMoveCompletion = {},
                onDismissCompletion = onDismiss,
                onStepHistory = { false },
                onPasteSpecial = { _, _ -> },
                onPickFiles = onPickFiles,
                onDropFiles = onDropFiles,
            ),
            canSubmit = canSubmit,
            busy = busy,
            stopping = false,
            onSend = { text ->
                onSend(text)
                state = state.copy(value = TextFieldValue(""))
            },
            onStop = {},
        )
    }

    @Test
    fun `Enter 发送并清空输入框`() = runComposeUiTest {
        var sent: String? = null
        setContent { HermesTheme { TestComposer(onSend = { sent = it }) } }

        onNode(hasSetTextAction()).performClick().performTextInput("你好，世界")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertEquals("你好，世界", sent)
        onNodeWithText("给 Hermes 发消息（Enter 发送，Shift+Enter 换行）").assertIsDisplayed()
    }

    @Test
    fun `补全浮层过期时 Enter 仍然发送`() = runComposeUiTest {
        // 浮层还在但已经没有可接受的项（文本在补全返回后又变了）：
        // 此时吞掉 Enter 会让"按回车没反应"，必须回落到发送。
        var sent: String? = null
        val state = ComposerUiState(
            value = TextFieldValue("/skills"),
            completion = CompletionState(
                trigger = CompletionTrigger.SLASH,
                entries = emptyList(),
                start = 0,
                end = 3,
            ),
        )
        setContent { HermesTheme { TestComposer(initial = state, onSend = { sent = it }) } }

        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertEquals("/skills", sent)
    }

    @Test
    fun `Shift加Enter不发送`() = runComposeUiTest {
        var sent: String? = null
        setContent { HermesTheme { TestComposer(onSend = { sent = it }) } }

        onNode(hasSetTextAction()).performClick().performTextInput("第一行")
        onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }

        assertEquals<Any?>(null, sent)
    }

    @Test
    fun `补全浮层打开时 Enter 接受而不是发送`() = runComposeUiTest {
        var sent: String? = null
        var accepted: Int? = -1
        val state = ComposerUiState(
            value = TextFieldValue("@co"),
            completion = CompletionState(
                trigger = CompletionTrigger.REFERENCE,
                entries = listOf(CompletionEntry(text = "@file:a.txt", display = "a.txt")),
                start = 0,
                end = 3,
            ),
        )
        setContent {
            HermesTheme {
                TestComposer(
                    initial = state,
                    onSend = { sent = it },
                    onAccept = {
                        accepted = it
                        true
                    },
                )
            }
        }

        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

        assertTrue(accepted != -1, "Enter 应该交给补全")
        assertEquals<Any?>(null, sent)
    }

    @Test
    fun `补全浮层可按 Esc 关闭`() = runComposeUiTest {
        var dismissed = false
        val state = ComposerUiState(
            value = TextFieldValue("@"),
            completion = CompletionState(
                trigger = CompletionTrigger.REFERENCE,
                entries = listOf(CompletionEntry(text = "@file:", display = "@file:")),
            ),
        )
        setContent { HermesTheme { TestComposer(initial = state, onDismiss = { dismissed = true }) } }

        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }

        assertTrue(dismissed)
    }

    @Test
    fun `生成中按上键仍能取历史`() = runComposeUiTest {
        // canSubmit=false 只应挡住"发送"，不该让输入框整体失效。
        var stepped = 0
        setContent {
            HermesTheme {
                Composer(
                    controller = ComposerController(
                        state = ComposerUiState(),
                        onValueChange = {},
                        onAcceptCompletion = { false },
                        onMoveCompletion = {},
                        onDismissCompletion = {},
                        onStepHistory = { delta ->
                            stepped += delta
                            false
                        },
                        onPasteSpecial = { _, _ -> },
                        onPickFiles = {},
                        onDropFiles = {},
                    ),
                    canSubmit = false,
                    busy = true,
                    stopping = false,
                    onSend = {},
                    onStop = {},
                )
            }
        }

        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) }

        assertEquals(-1, stepped)
    }

    @Test
    fun `点击附加按钮打开文件选择器`() = runComposeUiTest {        var picked = false
        setContent { HermesTheme { TestComposer(onPickFiles = { picked = true }) } }

        onNodeWithContentDescription("附加文件").performClick()

        assertTrue(picked)
    }

    @Test
    fun `附件与提示可见`() = runComposeUiTest {
        val state = ComposerUiState(
            attachments = listOf(ComposerAttachment(id = "a", label = "shot.png")),
            notice = "剪贴板里没有可用图片",
        )
        setContent { HermesTheme { TestComposer(initial = state) } }

        onNodeWithText("已附加：shot.png").assertIsDisplayed()
        onNodeWithText("剪贴板里没有可用图片").assertIsDisplayed()
    }

    @Test
    fun `生成中显示停止按钮`() = runComposeUiTest {
        var stopped = false
        setContent {
            HermesTheme {
                Composer(
                    controller = ComposerController(
                        state = ComposerUiState(),
                        onValueChange = {},
                        onAcceptCompletion = { false },
                        onMoveCompletion = {},
                        onDismissCompletion = {},
                        onStepHistory = { false },
                        onPasteSpecial = { _, _ -> },
                        onPickFiles = {},
                        onDropFiles = {},
                    ),
                    canSubmit = true,
                    busy = true,
                    stopping = false,
                    onSend = {},
                    onStop = { stopped = true },
                )
            }
        }

        onNodeWithContentDescription("停止").performClick()
        assertTrue(stopped)
    }

    @Test
    fun `禁用时发送按钮不可用`() = runComposeUiTest {
        setContent { HermesTheme { TestComposer(canSubmit = false) } }
        onNodeWithContentDescription("发送").assertIsNotEnabled()
    }
}
