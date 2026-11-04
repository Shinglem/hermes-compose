package dev.shinglem.hermes.markdown

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test

/**
 * 渲染冒烟：接的是 `:modules:markdown-one`，这里钉住「常见块类型都能画出来」。
 *
 * 只断言语义树里有对应文本，不比较像素 —— 换配色或间距不该让测试红，
 * 但「某类块被漏渲染」必须立刻暴露。
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownRenderTest {

    @Test
    fun `标题 列表 表格 与代码块都能渲染`() = runComposeUiTest {
        setContent {
            HermesTheme {
                MarkdownText(SAMPLE)
            }
        }
        onNodeWithText("一级标题").assertIsDisplayed()
        onNodeWithText("无序项二", substring = true).assertIsDisplayed()
        onNodeWithText("嵌套项", substring = true).assertIsDisplayed()
        onNodeWithText("高性能", substring = true).assertIsDisplayed()
        onNodeWithText("KOTLIN", substring = true).assertIsDisplayed()
    }

    @Test
    fun `流式未闭合栅栏不会把代码块渲染成普通段落`() = runComposeUiTest {
        setContent {
            HermesTheme {
                MarkdownText("```kotlin\nval streaming = true", streaming = true)
            }
        }
        onNodeWithText("KOTLIN", substring = true).assertIsDisplayed()
    }

    private companion object {
        val SAMPLE = """
            # 一级标题

            普通段落：**粗体**、*斜体*、`行内码`。

            - 无序项一
            - 无序项二
              - 嵌套项

            | 语言 | 用途 |
            | --- | --- |
            | Kotlin | 主力 |
            | Rust | 高性能 |

            ```kotlin
            data class Adapter(val id: String, val priority: Int)
            ```
        """.trimIndent()
    }
}
