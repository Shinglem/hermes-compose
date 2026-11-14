package dev.shinglem.hermes.component.subagent

import dev.shinglem.hermes.core.error.AppError

/** Reducer 的输入。 */
sealed interface SubagentResult {
    data class Opened(val open: Boolean) : SubagentResult

    data object Loading : SubagentResult

    data class Loaded(val entries: List<SubagentItem>) : SubagentResult

    data class Failed(val error: AppError, val unavailable: Boolean) : SubagentResult

    data class TailLoaded(val id: String, val tail: String) : SubagentResult

    data class Steering(val id: String?) : SubagentResult

    data class Noticed(val message: String?) : SubagentResult
}