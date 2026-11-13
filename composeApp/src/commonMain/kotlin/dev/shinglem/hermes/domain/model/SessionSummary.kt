package dev.shinglem.hermes.domain.model

import kotlin.time.Instant

/**
 * 会话列表行（`session.list` 的领域模型）。
 *
 * **为什么放在 `domain/model` 而不是 `feature/session`**：传输层（`core/transport/RestClient`）
 * 需要反序列化它。`core` 不得依赖 `feature`（架构规则 R2），否则依赖方向倒挂。
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Instant,
    val messageCount: Int,
    val source: String,
) {
    val displayTitle: String
        get() = title.ifBlank { preview.ifBlank { id } }
}
