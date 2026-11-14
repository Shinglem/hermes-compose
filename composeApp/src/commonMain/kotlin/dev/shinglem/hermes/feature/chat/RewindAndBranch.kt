package dev.shinglem.hermes.feature.chat

/**
 * 「重发 / 分支」这两个草稿的**序号口径**（纯函数，S8 从 `ChatViewModel` 抽出的判据）。
 *
 * 两处都容易算错，而且错法很隐蔽：
 *
 * - **重发**：要看"截断后历史是否为空"。算错不会报错，只是确认框少了一句
 *   「这会清空当前对话」的警告 —— 用户点下去才发现全没了。
 * - **分支**：`count` 是给后端的"保留前 N 条**可见**历史"。**不能拿 UI 列表下标**：
 *   UI 里还有工具卡片、通知条目、思考块，它们的下标与后端 `messages` 里的
 *   user/assistant 行完全不同（`ChatViewModel.beginBranch` 的注释记着这条）。
 */

/** 重发草稿的判定结果。 */
internal data class RewindTarget(
    val messageId: String,
    val text: String,
    /** 截断后历史为空（这条是第一条 user 消息）—— 确认框要额外警告。 */
    val emptyAfterTruncate: Boolean,
)

/**
 * 算出重发草稿；目标消息不在列表里时返回 `null`（调用方直接忽略这次点击）。
 *
 * @param items 当前 UI 条目（含工具卡片等非对话条目）
 */
internal fun rewindTarget(items: List<ChatItem>, messageId: String, text: String): RewindTarget? {
    val index = items.indexOfFirst { it.id == messageId }
    if (index < 0) return null
    // 截断到这条 user 消息之前：它前面一条 user 消息都没有 → 历史会变空。
    val priorUserCount = items.take(index).count { it is ChatItem.UserMessage }
    return RewindTarget(messageId = messageId, text = text, emptyAfterTruncate = priorUserCount == 0)
}

/** 分支草稿的判定结果。 */
internal data class BranchTarget(
    val messageId: String,
    /** 保留前 N 条**可见**历史（含这一条）。 */
    val count: Int,
    val title: String,
)

/**
 * 算出分支草稿；目标行不在**可见**历史里时返回 `null`。
 *
 * **可见** = `role` 是 user/assistant 且文本非空 —— 与后端 `messages` 的口径一致
 * （tool 行、hidden 行、空 assistant 行都不算）。
 *
 * @param rows 该会话的落库行（`ChatRow`，不是 UI 条目）
 * @param fallbackTitle `title` 为空时用它的默认值
 */
internal fun branchTarget(
    rows: List<ChatRow>,
    messageId: String,
    title: String,
    fallbackTitle: String = "分支",
): BranchTarget? {
    val visible = rows.filter { isVisibleHistoryRow(it) }
    val index = visible.indexOfFirst { it.id == messageId }
    if (index < 0) return null
    return BranchTarget(
        messageId = messageId,
        count = index + 1,
        title = title.ifBlank { fallbackTitle },
    )
}

/** 这一行是否算"可见历史"（分支的 `count` 与后端的口径都要按它对齐）。 */
internal fun isVisibleHistoryRow(row: ChatRow): Boolean =
    (row.role == ROLE_USER || row.role == ROLE_ASSISTANT) && row.text.isNotBlank()
