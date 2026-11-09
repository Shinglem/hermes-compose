package dev.shinglem.hermes.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.platform.touchDragScroll
import dev.shinglem.hermes.feature.composer.Composer
import dev.shinglem.hermes.feature.composer.ComposerController
import dev.shinglem.hermes.feature.composer.CompletionPopover
import dev.shinglem.hermes.feature.session.SessionSummary
import dev.shinglem.hermes.platform.Clipboard
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.chat_branch_body
import dev.shinglem.hermes.resources.chat_branch_cancel
import dev.shinglem.hermes.resources.chat_branch_ok
import dev.shinglem.hermes.resources.chat_branch_title
import dev.shinglem.hermes.resources.chat_empty_body
import dev.shinglem.hermes.resources.chat_empty_title
import dev.shinglem.hermes.resources.chat_history_start
import dev.shinglem.hermes.resources.chat_jump_to_latest
import dev.shinglem.hermes.resources.chat_load_older
import dev.shinglem.hermes.resources.chat_load_older_retry
import dev.shinglem.hermes.resources.chat_loading
import dev.shinglem.hermes.resources.chat_loading_older

import dev.shinglem.hermes.resources.chat_reload
import dev.shinglem.hermes.resources.chat_rewind_body
import dev.shinglem.hermes.resources.chat_rewind_body_empty
import dev.shinglem.hermes.resources.chat_rewind_cancel
import dev.shinglem.hermes.resources.chat_rewind_ok
import dev.shinglem.hermes.resources.chat_rewind_title
import dev.shinglem.hermes.resources.chat_streaming
import dev.shinglem.hermes.resources.chat_subagents
import dev.shinglem.hermes.resources.chat_untitled
import dev.shinglem.hermes.resources.detail_empty_body
import dev.shinglem.hermes.resources.detail_empty_title
import dev.shinglem.hermes.resources.detail_messages_count
import dev.shinglem.hermes.resources.detail_source
import dev.shinglem.hermes.resources.detail_started_at
import dev.shinglem.hermes.resources.model_confirm_body
import dev.shinglem.hermes.resources.model_confirm_cancel
import dev.shinglem.hermes.resources.model_confirm_ok
import dev.shinglem.hermes.resources.model_confirm_title
import dev.shinglem.hermes.ui.component.EmptyState
import dev.shinglem.hermes.ui.component.ErrorBanner
import dev.shinglem.hermes.ui.icon.AppIcons
import kotlinx.coroutines.channels.Channel

/**
 * 会话模式的右栏：顶栏 + 消息列表 + 审批卡片 + composer。
 *
 * 外层 `LazyColumn`，单条消息内部是普通 `Column`（硬约束 5）。
 * 滚动策略：默认吸底；用户滚轮上滚或触摸拖动后暂停跟随，向下滚 / 发新消息时恢复。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatPane(
    summary: SessionSummary?,
    state: ChatState,
    composer: ComposerController,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (requestId: String, choice: String) -> Unit,
    /** `clarify` 作答：`answers` 的键是 `qid`（单问固定 `q0`）。 */
    onClarifyAnswer: (requestId: String, answers: Map<String, String>) -> Unit = { _, _ -> },
    onClarifyCancel: (requestId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 引用一条消息（把它带进输入框），由外层接给 composer。 */
    onQuote: (String) -> Unit = {},
    /** composer 底部的模型 / 推理强度控件，由外层注入（本组件不认识模型域）。 */
    modelBar: (@Composable () -> Unit)? = null,
    onConfirmModelSwitch: () -> Unit = {},
    onDismissModelConfirm: () -> Unit = {},
    onDismissModelError: () -> Unit = {},
    /** 向上加载更早的一页。 */
    onLoadOlder: () -> Unit = {},
    /** 消息上的「重发」/「从这里分支」：打开确认框。 */
    onRewind: (ChatItem.UserMessage) -> Unit = {},
    onBranch: (ChatItem) -> Unit = {},
    onConfirmRewind: () -> Unit = {},
    onCancelRewind: () -> Unit = {},
    onConfirmBranch: () -> Unit = {},
    onCancelBranch: () -> Unit = {},
    /** 点开用量条：拉 `session.context_breakdown`。 */
    onShowUsageDetail: () -> Unit = {},
    onDismissUsageError: () -> Unit = {},
    /** 打开子代理面板；null = 不显示入口。 */
    onOpenSubagents: (() -> Unit)? = null,
    /** 后台任务条：终止 / 收起某条（`process.kill` 与本地忽略）。 */
    onKillBackgroundTask: (String) -> Unit = {},
    onDismissBackgroundTask: (String) -> Unit = {},
    /** 排队区：把这条立刻插进当前轮（`prompt.steer_queued`）/ 取消它（`prompt.cancel`）。 */
    onSteerQueued: (String) -> Unit = {},
    onCancelQueued: (String) -> Unit = {},
) {
    if (summary == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(Res.string.detail_empty_title),
                body = stringResource(Res.string.detail_empty_body),
                icon = AppIcons.Sessions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }
    val onCopy = remember { { text: String -> Clipboard.setText(text) } }
    // 向上加载的锚点：点击时记住「首项下标 + 偏移」，插入完成后按新增条数补滚。
    var olderAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(state.olderGeneration) {
        val anchor = olderAnchor ?: return@LaunchedEffect
        if (state.olderInserted <= 0) return@LaunchedEffect
        listState.scrollToItem(anchor.first + state.olderInserted, anchor.second)
        olderAnchor = null
    }

    val streaming = state.streaming
    val streamingExtra = if (streaming != null) 1 else 0
    val olderExtra = if (state.truncated || state.loadingOlder || state.items.isNotEmpty()) 1 else 0
    val totalItems = state.items.size + streamingExtra + olderExtra

    // 发新消息时重新跟随底部（用户此刻想看自己刚发的内容，而不是被上一次滚动状态挡住）。
    LaunchedEffect(state.sending) {
        if (state.sending) followTail = true
    }

    // 结构变化（切会话 / 新消息 / 新工具卡片）时定位到末尾一项。
    // 注意：**不要**把流式内容本身放进 key —— 那会让每个 token 都重置一次滚动位置，
    // 表现为"长回答时画面不停上下跳"。
    // offset 用 Int.MAX_VALUE：让**最后一项的底部**贴视口底边。默认 offset=0 会把这一项
    // 的顶部对齐视口顶部 —— 长回答时表现为「一直停在回答开头、看不到最新输出」。
    LaunchedEffect(state.key, totalItems) {
        if (followTail && totalItems > 0) listState.requestScrollToItem(totalItems - 1, Int.MAX_VALUE)
    }

    // 跟随底部：只在「有流式输出」时把新增内容滚进视口；同时负责「用户滚回底部就恢复跟随」。
    // snapshotFlow 监听的是**布局结果**（距底距离），滚完 distance 归零，不会自激。
    // 非流式时不动 —— 否则用户展开一个历史思考块也会把画面顶到底部（那是抢滚动）。
    //
    // 两个都不能踩的坑：
    // 1. `state` 每来一段输出就是一个**新的不可变快照**，而 LaunchedEffect 的 block 只捕获
    //    首次组合时那一个；直接读 `state.streaming` 会永远停在「没有流式输出」。
    //    所以走 rememberUpdatedState。
    // 2. collect 里**不能直接 await 滚动**：`scrollBy` / `scrollToItem` 都要排 `MutatorMutex`，
    //    撞上手势或正在进行的 `scrollToItem` 时会一直挂起，collect 卡死 → 后续 emission 被
    //    snapshotFlow conflate 掉 → 表现为「点了回到最新之后再也不跟随」。
    //    这里只往 conflate 通道里丢一个请求，真正的滚动由下面的独立循环顺序执行。
    val streamingNow by rememberUpdatedState(state.streaming != null)
    val totalItemsNow by rememberUpdatedState(totalItems)
    val followRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(state.key) {
        snapshotFlow { listState.distanceFromBottomPx() }.collect { distance ->
            if (streamingNow) {
                if (followTail && distance > 0) {
                    followRequests.trySend(Unit)
                } else if (!followTail && shouldResumeFollow(distance)) {
                    // 用户自己滚回底部：说明他想继续看新输出。
                    followTail = true
                }
            } else if (!followTail && shouldResumeFollow(distance)) {
                followTail = true
            }
        }
    }
    LaunchedEffect(state.key) {
        for (unused in followRequests) {
            val last = totalItemsNow - 1
            if (last >= 0) listState.requestScrollToItem(last, Int.MAX_VALUE)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ChatHeader(
            summary = summary,
            // 流式/新发的消息不落后端摘要刷新，这里以当前实际条目为准；未加载完前退回摘要值。
            messageCount = if (state.items.isEmpty()) summary.messageCount else state.items.size,
            onReload = onReload,
            usage = state.usage,
            usageDetail = state.usageDetail,
            onShowUsageDetail = onShowUsageDetail,
            onOpenSubagents = onOpenSubagents,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 触屏拖动（`scrollable` 不认鼠标类型拖动，见 touchDragScroll 说明）。
                .touchDragScroll(listState, onUserDrag = { followTail = false })
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                    // 只处理**垂直**滚动。Shift+滚轮 / 触摸板双指横划（以及公式块的横向滚动）
                    // 的 delta 全在 x 上、y 为 0；按 `y >= 0` 判定会被当成「向下滚」，
                    // 于是整篇文档跳到最底部 —— 真机反馈的就是这个。
                    if (abs(delta.x) >= abs(delta.y) || delta.y == 0f) return@onPointerEvent
                    // 向上翻且确实离开了底部才暂停跟随。轻微惯性 / 一格滚轮不算 ——
                    // 否则流式输出会在用户完全没察觉的情况下停止跟随。
                    if (shouldPauseFollow(delta.y, listState.distanceFromBottomPx())) {
                        followTail = false
                    }
                },
        ) {
            // 两侧留白随窗宽变化：触屏上手指落在文字里会被 SelectionContainer 接走变成**选中**
            // 而不是滚动，留一段空白（仍属于外层 touchDragScroll 的拖动区域）是最省事的规避。
            val sidePadding = chatSidePadding(maxWidth)
            val error = state.error
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(Res.string.chat_loading),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                error != null && state.items.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ErrorBanner(error = error, onRetry = onRetry)
                }

                state.items.isEmpty() && streaming == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = stringResource(Res.string.chat_empty_title),
                        body = stringResource(Res.string.chat_empty_body, ProductInfo.DISPLAY_NAME),
                        icon = AppIcons.Sessions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "older", contentType = "hint") {
                        OlderMessagesRow(
                            state = state,
                            onClick = {
                                olderAnchor = listState.firstVisibleItemIndex to
                                    listState.firstVisibleItemScrollOffset
                                onLoadOlder()
                            },
                        )
                    }
                    items(
                        items = state.items,
                        key = { it.id },
                        contentType = { it.kind },
                    ) { item ->
                        MessageItem(
                            item = item,
                            onCopy = onCopy,
                            onQuote = onQuote,
                            onRewind = onRewind,
                            onBranch = onBranch,
                        )
                    }
                    if (streaming != null) {
                        item(key = "streaming", contentType = "streaming") {
                            StreamingBlock(
                                state = streaming,
                                // 流式期间不包 SelectionContainer：内容每几十毫秒变一次，
                                // 选择状态反复重建会带来可见的闪烁与跳动。
                                selectable = false,
                            )
                        }
                    }
                }
            }

            // 正在接收回复且用户已向上翻看历史时，给一个回到底部的入口。
            // 用 canScrollForward 而不是 followTail：前者是"真的还有内容在下面"，
            // 后者只是"用户最后一次滚轮方向"。
            val atBottom by remember(listState) {
                derivedStateOf { !listState.canScrollForward }
            }
            JumpToLatestButton(
                visible = !atBottom && (state.streaming != null || state.sending),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 16.dp),
                onClick = {
                    followTail = true
                    if (totalItems > 0) listState.requestScrollToItem(totalItems - 1, Int.MAX_VALUE)
                },
            )
        }

        state.sendError?.let { sendError ->
            ErrorBanner(
                error = sendError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        // 模型切换失败：不静默 —— 否则 pill 会停在旧值，用户以为切成功了。
        state.modelError?.let { modelError ->
            ErrorBanner(
                error = modelError,
                onRetry = onDismissModelError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        // 用量读取失败不挡操作：一条可关闭的提示即可。
        state.usageError?.let { usageError ->
            ErrorBanner(
                error = usageError,
                onRetry = onDismissUsageError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        // 只画当前会话的审批：别的会话的在切过去时才出现（判据见 ChatState.visibleApprovals）。
        state.visibleApprovals.forEach { approval ->
            ApprovalCard(
                approval = approval,
                onDecide = { choice -> onApprove(approval.requestId, choice) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        // clarify：后端在等用户回答，卡片必须能作答（否则工具直接失败）。
        state.visibleClarify.forEach { clarify ->
            ClarifyCard(
                clarify = clarify,
                onAnswer = { answers -> onClarifyAnswer(clarify.requestId, answers) },
                onCancel = { onClarifyCancel(clarify.requestId) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        BackgroundTaskBar(
            tasks = state.backgroundTasks,
            killingTaskId = state.killingTaskId,
            onKill = onKillBackgroundTask,
            onDismiss = onDismissBackgroundTask,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        // 排队区：这些消息后端已接受但还没轮到执行。生成中再发的那条挂在这里，
        // 不插进当前轮的 transcript（否则它会跑到正在流式的思考过程前面）。
        if (state.queuedPrompts.isNotEmpty()) {
            QueuedPromptBar(
                items = state.queuedPrompts,
                onSteer = onSteerQueued,
                onCancel = onCancelQueued,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        composer.state.completion?.let { completion ->
            CompletionPopover(
                completion = completion,
                onPick = { index -> composer.onAcceptCompletion(index) },
            )
        }

        Composer(
            controller = composer,
            // 生成中也允许发（后端按 busy_input_mode 纠偏/排队），只有纠偏请求在飞时短暂禁用。
            canSubmit = !state.steering,
            busy = state.isBusy,
            stopping = state.stopping,
            onSend = onSend,
            onStop = onStop,
            modelBar = modelBar,
        )

        // 贵模型确认：后端 `config.set` 返回 confirm_required 时才出现。
        state.modelConfirm?.let { confirm ->
            AlertDialog(
                onDismissRequest = onDismissModelConfirm,
                title = { Text(stringResource(Res.string.model_confirm_title)) },
                text = {
                    Text(
                        confirm.message.ifBlank {
                            stringResource(Res.string.model_confirm_body, confirm.model)
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmModelSwitch) {
                        Text(stringResource(Res.string.model_confirm_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissModelConfirm) {
                        Text(stringResource(Res.string.model_confirm_cancel))
                    }
                },
            )
        }

        // 重发：截断是破坏性的，必须二次确认（后端的 confirm_truncate 只认客户端声明）。
        state.rewind?.let { draft ->
            AlertDialog(
                onDismissRequest = onCancelRewind,
                title = { Text(stringResource(Res.string.chat_rewind_title)) },
                text = {
                    Text(
                        if (draft.emptyAfterTruncate) {
                            stringResource(Res.string.chat_rewind_body_empty)
                        } else {
                            stringResource(Res.string.chat_rewind_body)
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmRewind) {
                        Text(stringResource(Res.string.chat_rewind_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelRewind) {
                        Text(stringResource(Res.string.chat_rewind_cancel))
                    }
                },
            )
        }

        state.branch?.let { draft ->
            AlertDialog(
                onDismissRequest = onCancelBranch,
                title = { Text(stringResource(Res.string.chat_branch_title)) },
                text = {
                    Text(stringResource(Res.string.chat_branch_body, draft.count))
                },
                confirmButton = {
                    TextButton(onClick = onConfirmBranch, enabled = !state.branching) {
                        Text(stringResource(Res.string.chat_branch_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelBranch) {
                        Text(stringResource(Res.string.chat_branch_cancel))
                    }
                },
            )
        }
    }
}

/** 列表顶部的「加载更早」入口：还有更早的消息时是按钮，加载中显示进度。 */
@Composable
private fun OlderMessagesRow(state: ChatState, onClick: () -> Unit) {
    val error = state.olderError
    when {
        state.loadingOlder -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(Res.string.chat_loading_older),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error != null -> TextButton(onClick = onClick) {
            Text(stringResource(Res.string.chat_load_older_retry))
        }

        state.truncated -> TextButton(onClick = onClick) {
            Text(stringResource(Res.string.chat_load_older))
        }

        else -> Text(
            text = stringResource(Res.string.chat_history_start),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「回到最新」浮动按钮。独立成函数是为了避开 `ColumnScope` / `BoxScope` 的
 * `AnimatedVisibility` 重载歧义（编译报 DSL_SCOPE_VIOLATION）。
 */
@Composable
private fun JumpToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(AppIcons.ArrowDown, contentDescription = null) },
            text = { Text(stringResource(Res.string.chat_jump_to_latest)) },
        )
    }
}

@Composable
private fun ChatHeader(
    summary: SessionSummary,
    messageCount: Int,
    onReload: () -> Unit,
    usage: SessionUsage?,
    usageDetail: String?,
    onShowUsageDetail: () -> Unit,
    onOpenSubagents: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 新建的会话还没拿到后端标题，用占位文案而不是露出 uuid。
                text = if (summary.title.isBlank() && summary.preview.isBlank()) {
                    stringResource(Res.string.chat_untitled)
                } else {
                    summary.displayTitle
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append(stringResource(Res.string.detail_messages_count, messageCount))
                if (summary.source.isNotBlank()) {
                    append(" · ")
                    append(stringResource(Res.string.detail_source, summary.source))
                }
                append(" · ")
                append(stringResource(Res.string.detail_started_at, TimeFormat.dateTimeText(summary.startedAt)))
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ContextUsageBar(
            usage = usage,
            detail = usageDetail,
            onShowDetail = onShowUsageDetail,
            modifier = Modifier.padding(end = 4.dp),
        )
        if (onOpenSubagents != null) {
            IconButton(onClick = onOpenSubagents) {
                Icon(
                    imageVector = AppIcons.Subagent,
                    contentDescription = stringResource(Res.string.chat_subagents),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onReload) {
            Icon(
                imageVector = AppIcons.Refresh,
                contentDescription = stringResource(Res.string.chat_reload),
            )
        }
    }
}

@Composable
private fun StreamingBlock(state: StreamingState, selectable: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.segments.forEach { segment ->
            when (segment) {
                is StreamSegment.Reasoning -> ReasoningBlock(text = segment.text)
                is StreamSegment.Answer -> AssistantAnswer(
                    text = segment.text,
                    selectable = selectable,
                    streaming = true,
                )

                is StreamSegment.Tool -> ToolCallCard(item = segment.item)
            }
        }
        if (state.isEmpty) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(Res.string.chat_streaming),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val ChatItem.kind: String
    get() = when (this) {
        is ChatItem.UserMessage -> "user"
        is ChatItem.AssistantMessage -> "assistant"
        is ChatItem.Tool -> "tool"
        is ChatItem.Notice -> "notice"
        is ChatItem.Reasoning -> "reasoning"
    }

/**
 * 消息内容两侧的留白。
 *
 * 触屏场景：手指落在文字上会被 `SelectionContainer` 接走，变成**选中**而不是滚动
 * （`touchDragScroll` 只在空白处能稳定接管拖动）。窄窗保底 [ChatSidePaddingMin]，
 * 宽窗按可用宽度的 6% 放大，上限 [ChatSidePaddingMax] —— 超宽屏上不至于把内容挤成一条。
 */
private val ChatSidePaddingMin = 24.dp
private val ChatSidePaddingMax = 96.dp

private fun chatSidePadding(available: Dp): Dp =
    (available * 0.06f).coerceIn(ChatSidePaddingMin, ChatSidePaddingMax)