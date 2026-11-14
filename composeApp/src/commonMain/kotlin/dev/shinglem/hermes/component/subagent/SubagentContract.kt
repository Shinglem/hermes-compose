package dev.shinglem.hermes.component.subagent

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.error.AppError

/** 子代理列表的一项（S5：从 `SubagentEntry` 迁来）。 */
@Immutable
data class SubagentItem(
    val id: String,
    val parentId: String? = null,
    val depth: Int = 0,
    val goal: String = "",
    val status: String? = null,
    val toolCount: Int = 0,
    val lastTool: String? = null,
    val model: String? = null,
    val acceptingSteer: Boolean = false,
    val tail: String = "",
)

/**
 * 会话级子代理面板的状态（S5：从 `SubagentState` 迁来）。
 *
 * `unavailable` 与 `isEmpty` 是两件不同的事：
 * - `unavailable`：后端没有子代理能力（或会话没有 runtime），面板显示"不可用"；
 * - `isEmpty`：能力有，但这个会话确实没有子代理，显示"暂无"。
 */
@Immutable
data class SubagentState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: AppError? = null,
    val entries: List<SubagentItem> = emptyList(),
    val steeringId: String? = null,
    val unavailable: Boolean = false,
    val notice: String? = null,
) {
    val running: Int get() = entries.count { it.status == STATUS_RUNNING }

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        const val STATUS_RUNNING = "running"
    }
}

sealed interface SubagentIntent {
    /** 开始订阅子代理事件（幂等）。Component 创建时自发一次。 */
    data object Observe : SubagentIntent

    data object Open : SubagentIntent
    data object Close : SubagentIntent

    /** 拉一次 roster（首屏 / 手动刷新）。 */
    data object Refresh : SubagentIntent

    /** 取某个子代理的最近输出（最多 16KB）。 */
    data class LoadTail(val subagentId: String) : SubagentIntent

    data class Steer(val subagentId: String, val text: String) : SubagentIntent

    data class Interrupt(val subagentId: String) : SubagentIntent

    data object DismissNotice : SubagentIntent
}