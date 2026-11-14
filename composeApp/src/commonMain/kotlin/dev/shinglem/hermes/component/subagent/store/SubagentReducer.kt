package dev.shinglem.hermes.component.subagent.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.subagent.SubagentResult
import dev.shinglem.hermes.component.subagent.SubagentState

/** 子代理面板的**纯**状态迁移。 */
internal class SubagentReducer : Reducer<SubagentState, SubagentResult> {

    override fun SubagentState.reduce(msg: SubagentResult): SubagentState = when (msg) {
        is SubagentResult.Opened -> copy(open = msg.open)

        SubagentResult.Loading -> copy(loading = true, unavailable = false, error = null)

        is SubagentResult.Loaded -> copy(
            loading = false,
            entries = msg.entries,
            unavailable = false,
            error = null,
        )

        is SubagentResult.Failed -> copy(
            loading = false,
            error = if (msg.unavailable) null else msg.error,
            // "后端没有这个能力"与"这次请求失败"要分开：前者不该显示成红色错误。
            unavailable = msg.unavailable,
            entries = if (msg.unavailable) emptyList() else entries,
        )

        is SubagentResult.TailLoaded -> copy(
            entries = entries.map { if (it.id == msg.id) it.copy(tail = msg.tail) else it },
        )

        is SubagentResult.Steering -> copy(steeringId = msg.id)

        is SubagentResult.Noticed -> copy(notice = msg.message)
    }
}