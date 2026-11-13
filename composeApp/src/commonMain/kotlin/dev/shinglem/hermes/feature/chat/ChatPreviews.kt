package dev.shinglem.hermes.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.shinglem.hermes.ui.theme.HermesTheme

/**
 * 聊天消息块的 IDE 预览（`13-重构落地设计` S1 第 7 项）。
 *
 * 只预览**纯渲染**的子块（`AssistantAnswer` / `ReasoningBlock`），不碰 `MessageItem` ——
 * 后者要完整的 `ChatItem` + 十几个回调，预览里拼出来的假数据反而会掩盖真实问题。
 *
 * 独立成文件的原因：`@Preview` 只在 IDE 里跑，放产品文件里会让它们多背一个 `ui-tooling-preview`
 * 依赖关系；集中在这里，将来要删也只删一个文件。
 */
@Preview(name = "助手回答 · 流式")
@Composable
private fun PreviewAssistantAnswerStreaming() {
    HermesTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AssistantAnswer(
                text = "正在生成**流式**回答…\n\n```kotlin\nval x = 1\n```",
                streaming = true,
            )
        }
    }
}

@Preview(name = "助手回答 · 完成")
@Composable
private fun PreviewAssistantAnswerDone() {
    HermesTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AssistantAnswer(
                text = "## 结论\n\n- 第一点\n- 第二点\n\n行内 `code` 与 [链接](https://example.com)",
                streaming = false,
            )
        }
    }
}

@Preview(name = "思考块 · 折叠")
@Composable
private fun PreviewReasoningCollapsed() {
    HermesTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ReasoningBlock(
                text = "先看调用链，再确认事件顺序，最后验证 fence 是否更新。",
                markdown = false,
                streaming = false,
            )
        }
    }
}
