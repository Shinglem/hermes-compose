package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后端契约的最小 Kotlin 映射（来源：`C:\ai\hermes\app\tui_gateway\contracts\`）。
 *
 * ⚠️ 本文件是**手抄子集**，不是生成产物。契约生成器落地后整包替换，
 * 见 `.design/草案/07-decisions.md` ADR-010。字段名必须与后端 wire 名一致。
 */

/** `session.list` → `SessionListRow`（`methods_session._session_row_summary`）。 */
@Serializable
data class SessionListRowDto(
    val id: String,
    @SerialName("resolved_id") val resolvedId: String? = null,
    val title: String = "",
    val preview: String = "",
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("message_count") val messageCount: Int = 0,
    val source: String = "",
)

@Serializable
data class SessionListResultDto(
    val sessions: List<SessionListRowDto> = emptyList(),
)

/**
 * `session.create` → `SessionCreateResult`（`tui_gateway/sessions.py:SessionCreateResult`）。
 *
 * `session_id` 是运行态句柄，`stored_session_id` 才是落库的会话 id —— 会话列表与
 * `session.history` / `session.resume` 用的都是后者。后者为空时（后端未分配）退回前者。
 */
@Serializable
data class SessionCreateResultDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
)

/** `profiles.list` → `ProfileRow`。 */@Serializable
data class ProfileDto(
    val name: String,
    val path: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val description: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("skill_count") val skillCount: Int = 0,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
)

@Serializable
data class ProfilesListResultDto(
    val profiles: List<ProfileDto> = emptyList(),
    @SerialName("bot_mode_protocol") val botModeProtocol: Boolean = true,
)

/** `session.history` → `TranscriptMessage`（P1 消息渲染用，P0 只做类型占位）。 */
@Serializable
data class TranscriptMessageDto(
    val role: String,
    val text: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val timestamp: Double? = null,
    @SerialName("row_id") val rowId: Long? = null,
    @SerialName("display_kind") val displayKind: String? = null,
    val name: String? = null,
    val context: String? = null,
)

@Serializable
data class SessionHistoryResultDto(
    val count: Int = 0,
    val messages: List<TranscriptMessageDto> = emptyList(),
)
