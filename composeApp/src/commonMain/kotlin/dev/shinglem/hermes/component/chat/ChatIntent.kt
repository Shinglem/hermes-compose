package dev.shinglem.hermes.component.chat

import dev.shinglem.hermes.feature.composer.PromptAttachment

/**
 * Chat 域的 Intent。
 *
 * 这一份是**从 `ChatViewModel` 的 182 个成员里挑出 UI 真正会调的那批**（用
 * `grep 'chatViewModel\.'` 在 `AppShell` 里点出来，共 30 个），而不是照抄整个公开面。
 * 其余成员（`ensureRuntime` / `publish` / `acknowledgeApproval` 等）是内部实现，
 * UI 不该看见 —— 这正是包 Component 的收益之一。
 *
 * 命名与 `ChatViewModel` 的方法名保持一一对应，方便对照与回滚。
 */
sealed interface ChatIntent {

    /**
     * 切到一个会话。[sessionId] 为空表示清空当前会话（回到"没选会话"）。
     * [force] 跳过缓存与快照，强制重读。
     */
    data class Open(val profile: String?, val sessionId: String?, val force: Boolean = false) : ChatIntent

    /** 重新读取当前会话的历史（`force` 语义同上）。 */
    data class Reload(val profile: String?, val sessionId: String?) : ChatIntent

    /**
     * 接管一个刚创建的空会话（本地先有 id、后端还没存）。
     *
     * 与 [Open] 分开是因为它**不该**丢弃本地已插入的用户消息：
     * 新建会话后立刻发第一条 prompt 时，那条消息已经在界面上。
     */
    data class AdoptEmpty(val profile: String?, val sessionId: String) : ChatIntent

    /** 向上加载更早的一页。 */
    data object LoadOlder : ChatIntent

    /** 发送一条 prompt（Markdown 源码 + 附件）。 */
    data class Send(val text: String, val attachments: List<PromptAttachment> = emptyList()) : ChatIntent

    /** 停止当前生成。 */
    data object Stop : ChatIntent

    /** 重连（用户点状态栏的重试）。 */
    data object Reconnect : ChatIntent

    // ── 审批 / 澄清 ─────────────────────────────────────────────────────

    /**
     * 作答审批。[decision] 取 `once` / `always` / `deny`。
     *
     * 注意 [requestId] 是**业务 id**（`approval.request` 的 `request_id`），
     * 不是 JSON-RPC 帧 id —— 由 Component 内部转换（帧 id 只用于 `request.cancel`）。
     */
    data class ResolveApproval(val requestId: String, val decision: String) : ChatIntent

    /**
     * 作答 `clarify`。[answers] 的键是 `qid`；空 map = 全部跳过。
     */
    data class AnswerClarify(val requestId: String, val answers: Map<String, String>) : ChatIntent

    /** 取消 `clarify`：后端按"用户放弃"处理。 */
    data class CancelClarify(val requestId: String) : ChatIntent

    // ── 排队消息 ────────────────────────────────────────────────────────

    /** 把排队项立刻插进当前轮（`prompt.steer_queued`）。 */
    data class SteerQueued(val requestId: String) : ChatIntent

    /** 取消一条排队项。 */
    data class CancelQueued(val requestId: String) : ChatIntent

    // ── 重发 / 分支 ─────────────────────────────────────────────────────

    /** 打开"重发"确认框（可编辑时的原始文本）。 */
    data class BeginRewind(val messageId: String, val text: String) : ChatIntent

    data object ConfirmRewind : ChatIntent

    data object CancelRewind : ChatIntent

    /** 打开"分支"确认框。 */
    data class BeginBranch(val messageId: String, val title: String) : ChatIntent

    data object ConfirmBranch : ChatIntent

    data object CancelBranch : ChatIntent

    // ── 后台任务 ────────────────────────────────────────────────────────

    data class KillBackgroundTask(val taskId: String) : ChatIntent

    /** 用户手动关掉一行；直到 registry 也不再报告该 id 才忘记。 */
    data class DismissBackgroundTask(val taskId: String) : ChatIntent

    // ── 模型 / 推理强度 ─────────────────────────────────────────────────

    /** 拉模型目录（下拉菜单展开时）。 */
    data object LoadModelCatalog : ChatIntent

    /** 切换当前会话的模型；贵模型会先弹确认框。 */
    data class SwitchModel(val provider: String, val model: String) : ChatIntent

    /** 确认"仍然使用贵模型"。 */
    data object ConfirmModelSwitch : ChatIntent

    data object DismissModelConfirm : ChatIntent

    data object DismissModelError : ChatIntent

    /** 改推理强度（`low` / `medium` / `high`）。 */
    data class SetReasoning(val effort: String) : ChatIntent

    // ── 用量 ────────────────────────────────────────────────────────────

    /** 展开上下文明细（点开时才拉 breakdown）。 */
    data object LoadUsageDetail : ChatIntent

    data object DismissUsageError : ChatIntent
}
