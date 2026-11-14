package dev.shinglem.hermes.component.chat

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import dev.shinglem.hermes.feature.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.CoroutineContext

/**
 * Chat 域的默认实现（形态见 [ChatComponent] 的类注释 + ADR-071）。
 *
 * ### 它做的事只有两件
 *
 * 1. **Scope 归属**：`ChatViewModel` 的 `viewModelScope` 换成 Component 的 scope，
 *    这样"聊天页被销毁"由 Decompose 的生命周期决定，而不是靠 androidx 的
 *    `ViewModelStoreOwner`（S5 之后 Shell 不再有那个 owner）。
 * 2. **回调 → Flow**：把 5 个 `var onXxx` 转成 [Channel] 驱动的 [effects]。
 *
 * 内部行为**零改动** —— 这是有意的：`ChatViewModel` 的六条线（流式/审批/快照/
 * 模型/用量/后台）交织在一起，任何一处改写都要连带验证其余五条。
 *
 * ### 回调为什么能安全转成 Channel
 *
 * 原实现里那 5 个 `var` 是"单消费者"的（外层 UI 赋值、`onDispose` 置 null）。
 * 现在 [effects] 只有 UI 一个消费者，语义等价；但多了一层保护：
 * **Channel 是 BUFFERED 的**，UI 重组期间发出的事件不会丢（旧实现里赋值/清空
 * 之间有窗口，通知中心与页面互相覆盖就是这么来的）。
 */
class DefaultChatComponent(
    private val componentContext: ComponentContext,
    repository: dev.shinglem.hermes.feature.chat.ChatRepository,
    gateway: dev.shinglem.hermes.core.transport.GatewayClient,
    models: dev.shinglem.hermes.feature.model.ModelRepository,
    preferences: dev.shinglem.hermes.core.prefs.AppPreferences,
    executorContext: CoroutineContext = Dispatchers.Default,
) : ChatComponent, ComponentContext by componentContext {

    private val holder: Holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        val scope = CoroutineScope(SupervisorJob() + executorContext)
        val model = ChatViewModel(repository, gateway, models, preferences, scope)
        // 一次性事件走 Channel：BUFFERED 保证 UI 重组期间不丢；
        // 与 S2 的 `labelsAsFlow()` 同一套纪律（一次性事件不进 State）。
        val channel = Channel<ChatEffect>(Channel.BUFFERED)
        model.onSessionPersisted = { channel.trySend(ChatEffect.SessionPersisted) }
        model.onTurnFinished = { failed, text ->
            channel.trySend(ChatEffect.TurnFinished(failed, text))
        }
        model.onApprovalRequested = { approval, key ->
            channel.trySend(ChatEffect.ApprovalRequested(approval, key))
        }
        model.onClarifyRequested = { clarify, key ->
            channel.trySend(ChatEffect.ClarifyRequested(clarify, key))
        }
        model.onBranched = { from -> channel.trySend(ChatEffect.Branched(from)) }
        Holder(model, scope, channel)
    }

    /**
     * 底层状态机。
     *
     * **过渡期暴露**（与 Maintenance 同一模式）：AppShell 里还有约 30 处直接用它的方法，
     * 逐条换成 onIntent 是纯机械劳动、收益只是"看起来更整齐"。终态重写（S8）时一并换掉。
     * UI 新代码请走 [onIntent]。
     */
    val model: ChatViewModel get() = holder.model

    override val state: StateFlow<dev.shinglem.hermes.feature.chat.ChatState> = model.state

    override val effects: Flow<ChatEffect> = holder.channel.receiveAsFlow()

    override val profile: String? get() = model.profile

    override val runtimeId: String? get() = model.runtimeInfo()?.runtimeId

    override fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.Open -> model.open(intent.profile, intent.sessionId, intent.force)
            is ChatIntent.Reload -> model.reload(intent.profile, intent.sessionId)
            is ChatIntent.AdoptEmpty -> model.adoptEmpty(intent.profile, intent.sessionId)
            ChatIntent.LoadOlder -> model.loadOlder()
            is ChatIntent.Send -> model.send(intent.text, intent.attachments)
            ChatIntent.Stop -> model.stop()
            ChatIntent.Reconnect -> model.reconnect()

            is ChatIntent.ResolveApproval -> model.resolveApproval(intent.requestId, intent.decision)
            is ChatIntent.AnswerClarify -> model.answerClarify(intent.requestId, intent.answers)
            is ChatIntent.CancelClarify -> model.cancelClarify(intent.requestId)

            is ChatIntent.SteerQueued -> model.steerQueuedPrompt(intent.requestId)
            is ChatIntent.CancelQueued -> model.cancelQueuedPrompt(intent.requestId)

            is ChatIntent.BeginRewind -> model.beginRewind(intent.messageId, intent.text)
            ChatIntent.ConfirmRewind -> model.confirmRewind()
            ChatIntent.CancelRewind -> model.cancelRewind()

            is ChatIntent.BeginBranch -> model.beginBranch(intent.messageId, intent.title)
            ChatIntent.ConfirmBranch -> model.confirmBranch()
            ChatIntent.CancelBranch -> model.cancelBranch()

            is ChatIntent.KillBackgroundTask -> model.killBackgroundTask(intent.taskId)
            is ChatIntent.DismissBackgroundTask -> model.dismissBackgroundTask(intent.taskId)

            ChatIntent.LoadModelCatalog -> model.loadModelCatalog()
            is ChatIntent.SwitchModel -> model.switchModel(intent.provider, intent.model)
            ChatIntent.ConfirmModelSwitch -> model.confirmModelSwitch()
            ChatIntent.DismissModelConfirm -> model.dismissModelConfirm()
            ChatIntent.DismissModelError -> model.dismissModelError()
            is ChatIntent.SetReasoning -> model.setReasoning(intent.effort)

            ChatIntent.LoadUsageDetail -> model.loadUsageDetail()
            ChatIntent.DismissUsageError -> model.dismissUsageError()
        }
    }

    /** ViewModel 与其 scope、事件通道一起装进 `InstanceKeeper`：Component 销毁时一并收。 */
    private class Holder(
        val model: ChatViewModel,
        val scope: CoroutineScope,
        val channel: Channel<ChatEffect>,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() {
            // **先**让 ViewModel 退订 server-request handler、取消所有待决的
            // `CompletableDeferred`，**再**取消 scope。顺序反过来的话，在途的审批决策
            // 会永远挂着（`gateway.removeServerRequestHandler` 之后再也不会被应答）。
            model.dispose()
            scope.cancel()
            channel.close()
        }
    }

    private companion object {
        const val STORE_KEY = "chat"
    }
}
