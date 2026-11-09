package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.markdown.MarkdownText
import dev.shinglem.hermes.platform.openExternalUrl
import dev.shinglem.hermes.platform.touchDragScroll
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
 * [streaming] 为 true 时块内**跟随最新**（见 [ReasoningBody]）。
 * 展开态限高并内部滚动，长思考不会把整轮对话挤出视口。
 */
@Composable
internal fun ReasoningBlock(text: String, markdown: Boolean = false, streaming: Boolean = false) {
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
            ReasoningBody(text = text, markdown = markdown, streaming = streaming)
        }
    }
}

/**
 * 思考块的正文：限高 + 块内滚动 + **流式时跟随最新** + 溢出时给滚动条。
 *
 * 为什么单独一层：外层 `ChatPane` 的补滚只管 `LazyColumn` 视口，管不到块内这个
 * `ScrollState` —— 长思考涨到 [REASONING_MAX_HEIGHT] 之后新内容落在视口下方，用户以为
 * "卡住不动了"（v0.5.3 反馈）。
 *
 * 跟随规则（与聊天流同一套判据，见 `ScrollFollow`）：
 * 1. 只在**流式**时跟随。历史思考段内容不再变，定位到开头才是对的。
 * 2. 用户接管 = `scroll.value` 变小（上翻）。滚轮 / 拖滚动条 / 触摸拖动都算。
 * 3. 回到贴底（含 [ScrollFollow.STICK_SLOP_PX] 余量）恢复跟随。内容增长只会让间距变大，
 *    所以「贴底」不会因为新内容而误判成用户回来。
 *
 * 补滚请求走 conflated 通道、由独立循环顺序执行：直接在 `snapshotFlow` 的 collect 里
 * `scrollTo` 会与用户手势抢 `MutatorMutex`，把 collect 挂死（外层聊天流踩过这个坑）。
 */
@Composable
private fun ReasoningBody(text: String, markdown: Boolean, streaming: Boolean) {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    var blockHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(streaming) {
        if (!streaming) return@LaunchedEffect
        var lastValue = scroll.value
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            val next = nextReasoningFollow(
                previousScrollValue = lastValue,
                scrollValue = value,
                maxValue = max,
                follow = follow,
            )
            lastValue = value
            follow = next.follow
        }
    }
    // 每来一段新内容就把块内视口滚到底。等一帧：`LaunchedEffect(text)` 在本次组合之后启动，
    // 此时新内容还没测量完，`maxValue` 仍是旧值。
    LaunchedEffect(streaming, text) {
        if (!streaming || !follow) return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }

    val overflow by remember { derivedStateOf { scroll.maxValue > 0 } }
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .onSizeChanged { blockHeightPx = it.height },
    ) {
        // 触摸拖动只在溢出时挂：draggable 没有「到边界就不消费」的语义，
        // 挂在不足一屏的块上会把本该滚外层对话的拖动吞掉。
        val frame = Modifier
            .fillMaxWidth()
            .heightIn(max = REASONING_MAX_HEIGHT)
            .then(if (overflow) Modifier.touchDragScroll(scroll) else Modifier)
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

        if (overflow) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(REASONING_SCROLLBAR_WIDTH)
                    // 尺寸显式给：`fillMaxHeight()` 在滚动容器里会退化成 Int.MAX_VALUE，
                    // Skia 直接拒绝（markdown-one 的 BlockScrollbarOverlay 记过这个坑）。
                    .height(with(density) { blockHeightPx.toDp() }),
            )
        }
    }
}

/** 展开态的最大高度：超过就在块内滚动，不把对话挤出视口。 */
private val REASONING_MAX_HEIGHT = 320.dp

private val REASONING_SCROLLBAR_WIDTH = 10.dp
