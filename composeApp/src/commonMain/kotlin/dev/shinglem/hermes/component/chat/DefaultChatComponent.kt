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
 * ### ⚠️ [executorContext] 必须是**单线程**的 `Dispatchers.Swing`
 *
 * 这不是保守选择，是**硬约束**。迁移前 `ChatViewModel : ViewModel()` 的全部协程跑在
 * `viewModelScope` 上，desktop 下即 `Dispatchers.Main.immediate`（= Swing EDT，
 * `build.gradle.kts` 专门加了 `kotlinx-coroutines-swing`）。也就是说它的全部代码路径
 * 被**隐式串行化在一条线程**上，而它持有一大把裸 `mutableMapOf` / `StringBuilder` /
 * `var localSeq`（`runtimeIds` / `savedStates` / `deltaBuffer` / …），全靠那条线程做互斥。
 *
 * 更关键：**server→client 请求处理器不在这个 scope 里** —— `GatewayClient` 用
 * container 的 `Dispatchers.Swing` 调 `answerServerRequest`，进而在
 * `awaitApprovalDecision` / `awaitClarifyDecision` 里写 `approvalDecisions` 并读
 * `runtimeIds`。如果这里换成 `Dispatchers.Default`（多线程 pool），两边就真正并发，
 * `runtimeIds` 的遍历会 `ConcurrentModificationException`、`deltaBuffer` 会串字。
 *
 * 所以**不要**把它改成 `Dispatchers.Default`；等 S8 把那些裸 map 收进
 * `SessionRuntimeStore` + 加同步之后，才谈得上换多线程。
 */
class DefaultChatComponent(
    private val componentContext: ComponentContext,
    repository: dev.shinglem.hermes.feature.chat.ChatRepository,
    gateway: dev.shinglem.hermes.core.transport.GatewayClient,
    models: dev.shinglem.hermes.feature.model.ModelRepository,
    preferences: dev.shinglem.hermes.core.prefs.AppPreferences,
    /**
     * **必须**传单线程 dispatcher（desktop 侧传 `Dispatchers.Swing`）。
     *
     * 故意不给默认值：它在本类所在的 `commonMain` 里不可用（`Dispatchers.Swing`
     * 是 `kotlinx-coroutines-swing` 的 JVM 扩展），而"随手用 `Dispatchers.Default`"
     * 会**改变并发模型**并引入数据竞争 —— 详见类注释。强制调用方显式选择，
     * 就不会有人在不知情的情况下踩这个坑。
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
