package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.contract.BranchResultDto
import dev.shinglem.hermes.core.contract.ToolLabelDto
import dev.shinglem.hermes.core.contract.TranscriptRowDto
import dev.shinglem.hermes.core.contract.toPlainText
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.core.transport.RpcJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlin.time.Instant
data class ResumedSession(
    val runtimeId: String,
    val cwd: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val reasoningEffort: String? = null,
    /**
     * `execution_generation`：0.23.0 的 canonical gateway 要求 `session.interrupt` /
     * `session.steer` / `approval.respond` / `clarify.respond` **必须**带上它
     * （缺了就是 `4001 invalid_params`），每次 `prompt.submit` 之后这个数会变，
     * 所以它只作为"当前这一代的凭据"快照。
     */
    val executionGeneration: Int? = null,
    /** 会话的 mutation revision，做 rename / archive 等编辑时的 fence。 */
    val revision: Int? = null,
    /** 会话此刻是否还在跑（`running`）：卡在 clarify 时会一直是 true。 */
    val running: Boolean = false,
    /**
     * 快照里**还没被应答**的请求（`prompts`）：`kind=clarify` 的那条只能靠
     * `clarify.respond` 解开，否则 turn 会一直停在 `waiting`。
     */
    val prompts: List<ResumedPrompt> = emptyList(),
    /** 快照里**还在排队**的消息（`pending[]`）：重连后队列不能丢。 */
    val pending: List<ResumedPending> = emptyList(),
)

/** `session.resume` 快照里的 `pending[]`（一条已提交、还没跑完的 admission）。 */
data class ResumedPending(
    val admissionId: String,
    val text: String,
    val status: String,
    val sequence: Int? = null,
) {
    val isQueued: Boolean get() = status == "queued"
}

/** `session.resume` 快照里的 `prompts[]`（`clarify` / `approval` 两条补拉路径都靠它）。 */
data class ResumedPrompt(
    val kind: String,
    val promptId: String,
    val executionGeneration: Int? = null,
    val question: String = "",
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    /** `kind=approval` 时的命令与说明。 */
    val command: String = "",
    val description: String = "",
)

data class PromptAck(
    /** `queued` / `started` / `unknown` / `terminal`（`AdmissionStatus.status`）。 */
    val status: String?,
    val userRowId: Long?,
    /** 这次提交的 admission 身份：排队时要靠它取消 / 插队。 */
    val admissionId: String? = null,
    val sequence: Int? = null,
    val executionGeneration: Int? = null,
) {
    /** 还在排队（后端接了但这一轮还没轮到它）。 */
    val isQueued: Boolean get() = status == "queued" || status == "unknown"
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * 一条 `kind=approval|clarify` 的待应答请求。
 *
 * **快照的 `prompts[]` 与事件 `approval.request` / `clarify.request` 的 payload 是同构的**，
 * 所以两条来源共用这一个解析器（见 `.design/详细设计/工程与代码/11-后端API调用规范.md` §5）。
 */
internal fun JsonObject.toResumedPrompt(): ResumedPrompt? {
    val kind = stringOrNull("kind") ?: return null
    val promptId = stringOrNull("prompt_id") ?: return null
    return ResumedPrompt(
        kind = kind,
        promptId = promptId,
        executionGeneration = (this["execution_generation"] as? JsonPrimitive)?.intOrNull,
        question = stringOrNull("question").orEmpty(),
        choices = (this["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList(),
        multiSelect = (this["multi_select"] as? JsonPrimitive)?.booleanOrNull == true,
        command = stringOrNull("command").orEmpty(),
        description = stringOrNull("description").orEmpty(),
    )
}

data class TranscriptPage(
    val sessionId: String,
    val rows: List<ChatRow>,
    val pageSize: Int,
    val returned: Int,
    /** 这一页的起点（从末尾往前算）；向上加载时用它累加。 */
    val offset: Int = 0,
) {
    /** 这一页是满的，说明更早的消息还没取回来。 */
    val hasMore: Boolean get() = returned >= pageSize && pageSize > 0

    /** 兼容旧调用点：语义同 [hasMore]。 */
    val truncated: Boolean get() = hasMore
}

/** 分支/复制的结果：`storedSessionId` 是要选中并落进列表的那个 id。 */
data class BranchFrom(val storedSessionId: String, val title: String, val messageCount: Int) {
    companion object {
        internal fun of(element: JsonElement, operation: String): BranchFrom {
            val obj = element as? JsonObject ?: throw AppError.Protocol("$operation is not an object")
            val dto = runCatching {
                RpcJson.json.decodeFromJsonElement(BranchResultDto.serializer(), obj)
            }.getOrElse { throw AppError.Protocol("$operation payload mismatch") }
            val stored = dto.storedSessionId?.takeIf { it.isNotBlank() } ?: dto.sessionId
            if (stored.isBlank()) throw AppError.Protocol("$operation returned no session id")
            return BranchFrom(stored, dto.title, dto.messageCount)
        }

        /**
         * `session.mutate {operation:"branch"}` 的返回：
         * `{session_id, revision, execution_generation, operation, branched_session_id, title?}`。
         * 新会话 id 在 `branched_session_id` 上，不会同时给 `stored_session_id`。
         */
        internal fun ofMutation(element: JsonElement, operation: String): BranchFrom {
            val obj = element as? JsonObject ?: throw AppError.Protocol("$operation is not an object")
            val stored = obj.stringOrNull("branched_session_id")
                ?: obj.stringOrNull("stored_session_id")
                ?: obj.stringOrNull("session_id")
                ?: throw AppError.Protocol("$operation returned no session id")
            return BranchFrom(
                storedSessionId = stored,
                title = obj.stringOrNull("title").orEmpty(),
                messageCount = (obj["message_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
    }
}

/** 落库行的领域投影（尚未配对工具调用）。 */
data class ChatRow(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Instant?,
    val toolName: String?,
    val toolCallId: String?,
    val toolCalls: List<ChatToolCall>,
    val labels: Map<String, List<ToolLabelDto>>,
    val displayKind: String?,
    val hidden: Boolean,
    /** `stop` / `tool_calls` / `length` / `content_filter` / `incomplete`（后端 `finish_reason`）。 */
    val finishReason: String? = null,
    /** 这一步的思考过程（每步一条 assistant 行各带一份，重开历史按步分段还原）。 */
    val reasoning: String? = null,
)

data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

internal fun TranscriptRowDto.toRow(): ChatRow = ChatRow(
    id = id.ifBlank { "row-$timestamp-$role" },
    role = role,
    text = content.toPlainText(),
    timestamp = timestamp?.let(TimeFormat::fromEpochSeconds),
    toolName = toolName,
    toolCallId = toolCallId,
    toolCalls = toolCalls.orEmpty().mapNotNull { call ->
        val id = call.id ?: return@mapNotNull null
        ChatToolCall(
            id = id,
            name = call.function?.name.orEmpty().ifBlank { "tool" },
            arguments = call.function?.arguments.orEmpty(),
        )
    },
    labels = toolCallLabels.orEmpty(),
    displayKind = displayKind,
    hidden = displayKind == HIDDEN_DISPLAY_KIND,
    finishReason = finishReason,
    // 优先级与后端 `acp_adapter/server.py::_history_reasoning_text` 一致。
    reasoning = reasoningContent?.takeIf { it.isNotBlank() } ?: reasoning?.takeIf { it.isNotBlank() },
)

internal const val HIDDEN_DISPLAY_KIND = "hidden"
