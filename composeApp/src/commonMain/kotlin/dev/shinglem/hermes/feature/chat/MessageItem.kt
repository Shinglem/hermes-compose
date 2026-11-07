package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.markdown.MarkdownText
import dev.shinglem.hermes.platform.openExternalUrl
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.chat_branch_message
import dev.shinglem.hermes.resources.chat_copy_message
import dev.shinglem.hermes.resources.chat_mermaid_fallback
import dev.shinglem.hermes.resources.chat_quote_message
import dev.shinglem.hermes.resources.chat_reasoning
import dev.shinglem.hermes.resources.chat_rewind_message
import dev.shinglem.hermes.resources.chat_role_assistant
import dev.shinglem.hermes.resources.chat_role_user
import dev.shinglem.hermes.ui.icon.AppIcons

/**
 * 一条消息。选区由 markdown-one 的渲染层内部提供（硬约束 6：跨消息选择不可用，不试图修）。
 * hover 时右上角出现「复制整条」/「引用」，以及 v0.3.0 的「重发」「从这里分支」。
 */
@Composable
fun MessageItem(
    item: ChatItem,
    onCopy: (String) -> Unit,
    onQuote: (String) -> Unit = {},
    onRewind: ((ChatItem.UserMessage) -> Unit)? = null,
    onBranch: ((ChatItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(modifier = modifier.fillMaxWidth().hoverable(interaction)) {
        when (item) {
            is ChatItem.UserMessage -> UserMessageBlock(item)
            is ChatItem.AssistantMessage -> AssistantAnswer(item.markdown)
            is ChatItem.Tool -> ToolCallCard(item)
            is ChatItem.Notice -> NoticeRow(item)
            is ChatItem.Reasoning -> ReasoningBlock(item.text, markdown = true)
        }
        if (hovered) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row {
                    IconButton(
                        onClick = { onQuote(item.copyText()) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Quote,
                            contentDescription = stringResource(Res.string.chat_quote_message),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onCopy(item.copyText()) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Copy,
                            contentDescription = stringResource(Res.string.chat_copy_message),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onBranch != null && item is ChatItem.UserMessage) {
                        IconButton(
                            onClick = { onBranch(item) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Branch,
                                contentDescription = stringResource(Res.string.chat_branch_message),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onRewind != null && item is ChatItem.UserMessage) {
                        IconButton(
                            onClick = { onRewind(item) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Rewind,
                                contentDescription = stringResource(Res.string.chat_rewind_message),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserMessageBlock(item: ChatItem.UserMessage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(Res.string.chat_role_user),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            MarkdownText(
                item.text,
                mermaidFallbackText = stringResource(Res.string.chat_mermaid_fallback),
                onLinkClick = { url -> openExternalUrl(url) },
            )
        }
    }
}

@Composable
internal fun AssistantAnswer(text: String, selectable: Boolean = true, streaming: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(Res.string.chat_role_assistant),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fallback = stringResource(Res.string.chat_mermaid_fallback)
        val onLink: (String) -> Unit = remember { { url -> openExternalUrl(url) } }
        MarkdownText(
            text,
            streaming = streaming,
            mermaidFallbackText = fallback,
            selectable = selectable,
            onLinkClick = onLink,
        )
    }
}

@Composable
private fun NoticeRow(item: ChatItem.Notice) {
    Text(
        text = item.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * 思考过程块：默认**展开**（用户要能直接看到在想什么），点标题折叠。
 *
 * [markdown] 只在**已经写完**的思考段上开（`ChatItem.Reasoning`）：
 * 流式期间是逐 token 的增量，每来一个字就重解析一遍会把 CPU 峰值翻倍，
 * 未闭合的代码栅栏 / 表格还会反复重排。
 * 展开态限高并内部滚动，长思考不会把整轮对话挤出视口。
 */
@Composable
internal fun ReasoningBlock(text: String, markdown: Boolean = false) {
    var expanded by remember { mutableStateOf(true) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Icon(
                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.chat_reasoning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!expanded) return@Column

            val scroll = rememberScrollState()
            val frame = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .heightIn(max = REASONING_MAX_HEIGHT)
                .verticalScroll(scroll)

            if (markdown) {
                MarkdownText(
                    markdown = text,
                    modifier = frame,
                    mermaidFallbackText = stringResource(Res.string.chat_mermaid_fallback),
                    onLinkClick = { url -> openExternalUrl(url) },
                )
            } else {
                Box(frame) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 展开态的最大高度：超过就在块内滚动，不把对话挤出视口。 */
private val REASONING_MAX_HEIGHT = 320.dp
