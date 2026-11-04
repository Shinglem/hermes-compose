package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * v0.3.0 新增契约的最小映射（手抄子集，来源见各字段注释）。
 *
 * 全部字段带默认值：后端增减字段时客户端不能因为一个未知键整包解析失败。
 */

/**
 * `session.usage` 的返回（`tui_gateway/methods_session.py` → `_session_usage_snapshot`）。
 *
 * 顶层就是 usage 对象本身（不是 `{usage: {...}}`），另外可能带 `credits_lines` /
 * `account_lines` 两个字符串数组。
 */
@Serializable
data class UsageDto(
    val calls: Int = 0,
    val input: Long = 0,
    val output: Long = 0,
    val total: Long = 0,
    @SerialName("context_max") val contextMax: Long = 0,
    @SerialName("context_used") val contextUsed: Long = 0,
    @SerialName("context_percent") val contextPercent: Double = 0.0,
    @SerialName("context_estimated") val contextEstimated: Boolean = false,
    @SerialName("context_source") val contextSource: String? = null,
    @SerialName("credits_lines") val creditsLines: List<String> = emptyList(),
    @SerialName("account_lines") val accountLines: List<String> = emptyList(),
)

/**
 * `session.context_breakdown` 的顶层字段。
 *
 * `categories` / `context_files` 的结构随后端版本变过，这里只保留 UI 真正要用的四个数字
 * 与模型名；其余原始 JSON 收在 [categories] 里备用（不渲染）。
 */
@Serializable
data class ContextBreakdownDto(
    @SerialName("context_max") val contextMax: Long = 0,
    @SerialName("context_used") val contextUsed: Long = 0,
    @SerialName("context_percent") val contextPercent: Double = 0.0,
    @SerialName("estimated_total") val estimatedTotal: Long = 0,
    @SerialName("context_estimated") val contextEstimated: Boolean = false,
    @SerialName("context_source") val contextSource: String? = null,
    val model: String = "",
    val categories: JsonElement? = null,
)

/**
 * `session.branch` / `session.branch_whole` / `session.branch_stored` 的返回。
 *
 * `session_id` 是新建的**运行时** id（分支结果刚建好，是 live 的）；
 * `stored_session_id` 才是要选中并落进列表的那个。
 */
@Serializable
data class BranchResultDto(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    val title: String = "",
    val parent: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
)

/**
 * `subagent.list` 的一项（`_SUBAGENT_SNAPSHOT_FIELDS`）。
 *
 * 身份字段全部可空：老版本 emitter 不带 `parent_id` / `depth`，扁平渲染即可。
 */
@Serializable
data class SubagentDto(
    @SerialName("subagent_id") val subagentId: String = "",
    @SerialName("parent_id") val parentId: String? = null,
    val depth: Int? = null,
    val goal: String = "",
    @SerialName("delegation_id") val delegationId: String? = null,
    val model: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    val status: String? = null,
    @SerialName("tool_count") val toolCount: Int? = null,
    @SerialName("last_tool") val lastTool: String? = null,
    @SerialName("accepting_steer") val acceptingSteer: Boolean? = null,
)

@Serializable
data class SubagentListDto(
    val subagents: List<SubagentDto> = emptyList(),
    val delegations: List<JsonElement> = emptyList(),
)

@Serializable
data class SubagentTailDto(
    @SerialName("subagent_id") val subagentId: String = "",
    val available: Boolean = false,
    val text: String = "",
    val truncated: Boolean = false,
)

/** `GET /api/sessions/search?q=`（`web_routers/sessions.py`）。 */
@Serializable
data class SessionSearchResultDto(
    @SerialName("session_id") val sessionId: String = "",
    val title: String? = null,
    val snippet: String = "",
    val role: String? = null,
    val model: String? = null,
    val source: String? = null,
    @SerialName("session_started") val sessionStarted: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
)

@Serializable
data class SessionSearchResponseDto(
    val results: List<SessionSearchResultDto> = emptyList(),
)

/** `GET /api/hermes/update/check`（**后端**更新，不是客户端自身更新）。 */
@Serializable
data class BackendUpdateCheckDto(
    @SerialName("install_method") val installMethod: String = "",
    @SerialName("current_version") val currentVersion: String = "",
    val behind: Int? = null,
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("can_apply") val canApply: Boolean = false,
    @SerialName("update_command") val updateCommand: String? = null,
    val message: String? = null,
)
