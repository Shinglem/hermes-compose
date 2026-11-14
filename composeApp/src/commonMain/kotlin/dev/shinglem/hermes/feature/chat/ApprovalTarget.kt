package dev.shinglem.hermes.feature.chat

/**
 * 审批应答的**目标三元组**（`approval.respond` 要的三个参数）。
 *
 * 抽出来是因为这里有一个**实测踩过的坑**：
 *
 * 用户可能在**另一个会话**里点系统通知上的「允许一次」。这时如果现查
 * `_state.value.key` / `runtimes[当前会话]`，拿到的是**另一个会话**的值 ——
 * 后端回 `4001 stale_generation`（实测：切会话后点通知必报）。
 *
 * 所以顺序必须是「**卡片自己记的** → 当前会话的兜底 → 放弃」：
 * 1. `sessionKey`：卡片登记时记下的归属会话；
 * 2. `runtimeSessionId`：卡片上带的 runtime id；
 * 3. `executionGeneration`：卡片上带的 fence。
 *
 * 三者都能从卡片拿到时，**一个都不许用当前会话的值覆盖**。
 */
internal data class ApprovalTarget(
    val key: SessionKey,
    val runtimeId: String,
    val generation: Int?,
)

/**
 * 算出审批应答的目标；缺关键信息时返回 `null`（调用方直接放弃，不要瞎猜）。
 *
 * @param fallbackKey 当前打开的会话（卡片没记 `sessionKey` 时的兜底）
 * @param fallbackRuntimeId 该会话当前的 runtime id
 * @param fallbackGeneration 该会话当前的 fence
 */
internal fun approvalTarget(
    pending: PendingApproval,
    fallbackKey: SessionKey?,
    fallbackRuntimeId: String?,
    fallbackGeneration: Int?,
): ApprovalTarget? {
    // 顺序不能颠倒：卡片自己记的值优先，兜底只在缺失时用。
    val key = pending.sessionKey ?: fallbackKey ?: return null
    // runtimeId 兜底要用**卡片所属会话**的，不是当前会话的 —— 否则跨会话点通知必然 4001。
    val runtimeId = pending.runtimeSessionId
        ?: (if (key == fallbackKey) fallbackRuntimeId else null)
        ?: return null
    val generation = pending.executionGeneration
        ?: (if (key == fallbackKey) fallbackGeneration else null)
    return ApprovalTarget(key = key, runtimeId = runtimeId, generation = generation)
}
