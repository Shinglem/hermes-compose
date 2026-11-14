package dev.shinglem.hermes.component.chat

import dev.shinglem.hermes.feature.chat.BranchFrom
import dev.shinglem.hermes.feature.chat.ChatState
import dev.shinglem.hermes.feature.chat.PendingApproval
import dev.shinglem.hermes.feature.chat.PendingClarify
import dev.shinglem.hermes.feature.chat.SessionKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chat 域对外的一次性事件。
 *
 * 迁移前它们是 `ChatViewModel` 上的 **5 个 `var onXxx: ((...) -> Unit)?`** ——
 * 那种形状有三个害处：
 * 1. **谁都能覆写**：两处都赋值，后写的静默盖掉前一个（外层 UI 与通知中心就这样互相踩过）；
 * 2. **没有"发生了"的语义**：赋值只是登记回调，读代码看不出"什么时候会触发"；
 * 3. **不能有多个消费者**：加第二个监听就得再塞一个 `var`。
 *
 * 换成 `Flow<ChatEffect>` 后：可以多消费者、不会互相覆盖、类型上就写明"这是一次性事件"
 * （与 S2 定的 `Label` 纪律一致 —— 一次性事件**不进 State**）。
 */
sealed interface ChatEffect {
    /** 一条 prompt 被后端接受（后端要到第一条 prompt 才生成会话列表行）。 */
    data object SessionPersisted : ChatEffect

    /** 一轮结束。[failed] 为真表示这轮失败。 */
    data class TurnFinished(val failed: Boolean, val text: String) : ChatEffect

    /** 收到审批请求（同一 `requestId` 只会发一次）。 */
    data class ApprovalRequested(val approval: PendingApproval, val key: SessionKey?) : ChatEffect

    /** 收到 `clarify` 请求。 */
    data class ClarifyRequested(val clarify: PendingClarify, val key: SessionKey?) : ChatEffect

    /** 分支 / 复制成功：外层据此刷新列表并选中新会话。 */
    data class Branched(val from: BranchFrom) : ChatEffect
}

/**
 * Chat 域的 Component。
 *
 * ### 这一版的形态（务必先读 ADR-071）
 *
 * `ChatViewModel`（2147 行）是**整个客户端最复杂的单文件**：流式状态机、审批/澄清、
 * 切会话快照、模型切换、用量、后台任务六条线互相交织。设计稿的终态是
 * 「`ChatReducer` 吃三份流式实现 + `SessionShadow`/`SessionSwitchPlan` 删除」，
 * 但那是 **3000+ 行的整块替换**，任何一条线改错都是"聊天时数据丢掉"级别。
 *
 * 所以这一版的做法与 S2 的 Maintenance 同构：**把 ViewModel 包进 Component，
 * 消灭它对外暴露的巨型接口面**（182 个成员 + 5 个可变回调），
 * 内部行为零改动。收益是 UI 侧只认 `state` / `effects` / `onIntent`；
 * 代价是 `ChatViewModel` 仍在（`feature/chat/`），终态重写排到 S8。
 *
 * **UI 侧纪律**：只通过本接口访问 Chat，不要直接引用 `ChatViewModel`。
 */
interface ChatComponent {
    val state: StateFlow<ChatState>

    /**
     * 一次性事件流。
     *
     * ### 是**广播**，不是队列
     *
     * 实现是 `SharedFlow(replay = 0, extraBufferCapacity = 64)`：**每个收集者都会收到
     * 全部事件**（fan-out）。这与"单消费者队列"（`Channel.receiveAsFlow`）不同 ——
     * 后者第二个收集者会和第一个**抢事件**，两边各拿到一部分，症状是"通知有时弹有时不弹"。
     *
     * 所以：可以安全地给 UI 和通知中心各加一个收集者。但要注意
     * **`replay = 0`**：收集者开始收集之前发出的事件收不到（不存在"补发"）。
     * 因此各方的收集器都应该在 composition 一开始就挂上，而不是"用到时才 collect"。
     */
    val effects: Flow<ChatEffect>

    fun onIntent(intent: ChatIntent)

    /**
     * 当前会话所属的 profile（`null` = 还没选会话）。
     *
     * 子代理页要用它 + [runtimeId] 拼 `RuntimeContext`。与 [state] 里的
     * `key.profile` 等价，但调用方不必先解 `SessionKey`。
     */
    val profile: String?

    /**
     * 当前会话的 runtime id（`session.resume` 的返回值）；没有活跃 runtime 时 null。
     *
     * 子代理页依赖它：子代理是**挂在某个 runtime 上**的进程，拿不到就查不了。
     */
    val runtimeId: String?
}
