package dev.shinglem.hermes.component.chat

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import dev.shinglem.hermes.feature.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
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
 * ### ⚠️ [executorContext] 必须是**单线程**的 UI dispatcher
 *
 * 这不是保守选择，是**硬约束**。`ChatViewModel` 持有一大把裸 `mutableMapOf` /
 * `StringBuilder` / `var localSeq`（`runtimeIds` / `savedStates` / `deltaBuffer` / …），
 * 全靠"全部代码路径被隐式串行化在一条线程"做互斥。
 *
 * 而且**这条线程必须与 `GatewayClient` 的同一条**：server→client 请求处理器跑在
 * container 的 scope 上，会调 `answerServerRequest`，进而在
 * `awaitApprovalDecision` / `awaitClarifyDecision` 里写 `approvalDecisions` 并读
 * `runtimeIds`。换成 `Dispatchers.Default`（多线程 pool）两边就真正并发，
 * `runtimeIds` 的遍历会 `ConcurrentModificationException`、`deltaBuffer` 会串字。
 *
 * desktop 侧要传 `UiDispatcher.context`，**不是** `Dispatchers.Swing` —— S6 迁 Nucleus Tao
 * 之后 `Dispatchers.Main` 才是 UI 线程（Compose 组合与 UI 事件回调都在 `main` 上），
 * `Dispatchers.Swing` 是另一条 `AWT-EventQueue-0`。判据与实测见 `platform/UiDispatcher.kt`。
 *
 * 等 S8 把那些裸 map 收进 `SessionRuntimeStore` + 加同步之后，才谈得上换多线程。
 */
class DefaultChatComponent(
    private val componentContext: ComponentContext,
    repository: dev.shinglem.hermes.feature.chat.ChatRepository,
    gateway: dev.shinglem.hermes.core.transport.GatewayClient,
    models: dev.shinglem.hermes.feature.model.ModelRepository,
    preferences: dev.shinglem.hermes.core.prefs.AppPreferences,
    /**
     * **必须**是 UI 线程（desktop 侧传 `UiDispatcher.context`，S6 后 = `Dispatchers.Main`）。
     *
     * 故意不给默认值：`Dispatchers.Swing` 曾是 desktop 的 UI 线程，但迁到 Nucleus Tao
     * 之后它变成了**另一条**线程（`AWT-EventQueue-0`），用错就等于把 UI 事件回调
     * （`main`）与状态变更（Swing EDT）拆到两条线程上。强制调用方显式选择，
     * 就不会有人在不知情的情况下踩这个坑 —— 判据与实测见 `platform/UiDispatcher.kt`。
     */
    executorContext: CoroutineContext,
) : ChatComponent, ComponentContext by componentContext {

    private val holder: Holder = componentContext.instanceKeeper.getOrCreate(key = STORE_KEY) {
        val scope = CoroutineScope(SupervisorJob() + executorContext)
        val model = ChatViewModel(repository, gateway, models, preferences, scope)
        // 一次性事件走 **SharedFlow 广播**而不是 Channel：
        // Channel 是"队列"语义，第二个收集者会和第一个抢事件（各拿一部分）；
        // SharedFlow 是"广播"，UI 与通知中心各收一份全量。
        // `extraBufferCapacity = 64`：收集者短暂忙时不丢；`replay = 0`：
        // 不补发历史（订阅前的事件收不到，见 ChatComponent.effects 的注释）。
        val effects = MutableSharedFlow<ChatEffect>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        model.onSessionPersisted = { effects.tryEmit(ChatEffect.SessionPersisted) }
        model.onTurnFinished = { failed, text ->
            effects.tryEmit(ChatEffect.TurnFinished(failed, text))
        }
        model.onApprovalRequested = { approval, key ->
            effects.tryEmit(ChatEffect.ApprovalRequested(approval, key))
        }
        model.onClarifyRequested = { clarify, key ->
            effects.tryEmit(ChatEffect.ClarifyRequested(clarify, key))
        }
        model.onBranched = { from -> effects.tryEmit(ChatEffect.Branched(from)) }
        Holder(model, scope, effects)
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

    override val effects: Flow<ChatEffect> = holder.effects.asSharedFlow()

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
        val effects: MutableSharedFlow<ChatEffect>,
    ) : InstanceKeeper.Instance {
        override fun onDestroy() {
            // **先**让 ViewModel 退订 server-request handler、取消所有待决的
            // `CompletableDeferred`，**再**取消 scope。顺序反过来的话，在途的审批决策
            // 会永远挂着（`gateway.removeServerRequestHandler` 之后再也不会被应答）。
            //
            // SharedFlow 没有 close：收集者随各自的 scope（composition）一起结束，
            // 不需要在销毁时主动关闭。
            model.dispose()
            scope.cancel()
        }
    }

    private companion object {
        const val STORE_KEY = "chat"
    }
}
