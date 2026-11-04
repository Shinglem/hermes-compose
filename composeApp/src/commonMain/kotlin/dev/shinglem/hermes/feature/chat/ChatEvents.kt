package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.contract.toPlainText
import dev.shinglem.hermes.core.transport.GatewayEvent
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 本轮对话相关的事件，已从 wire payload 投影成领域对象。 */
sealed interface ChatStreamEvent {
    data object Ignored : ChatStreamEvent
    data object TurnStarted : ChatStreamEvent
    data class Delta(val text: String) : ChatStreamEvent

    /** 工具调用旁边的旁白段落（`message.interim`）：后端把它封成独立一段，
     *  `alreadyStreamed` 为真表示这段文字已经通过 `message.delta` 流出过。 */
    data class Interim(val text: String, val alreadyStreamed: Boolean) : ChatStreamEvent

    /** 思考过程增量（`reasoning.delta`）。 */
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    data class Completed(
        val text: String,
        val status: String?,
        val failed: Boolean,
        val error: String?,
    ) : ChatStreamEvent

    data class ToolStarted(
        val toolId: String,
        val name: String,
        val label: String?,
        val arguments: String,
    ) : ChatStreamEvent

    data class ToolCompleted(
        val toolId: String,
        val name: String,
        val result: String?,
        val failed: Boolean,
    ) : ChatStreamEvent

    data class UsageUpdated(val totalTokens: Int?) : ChatStreamEvent
    data class TitleChanged(val title: String) : ChatStreamEvent
    data class Failure(val message: String) : ChatStreamEvent

    /**
     * `request.cancel`：后端撤回了一个等待中的 server→client 请求（超时 / 中断 / 别的界面已答）。
     *
     * 审批卡片必须据此消失，否则会留下一个点了也没用的按钮。
     */
    data class ApprovalCancelled(val requestId: String, val reason: String?) : ChatStreamEvent

    /** `request.cancel` 撤回了 `clarify`：卡片必须消失，等待按"取消"收尾。 */
    data class ClarifyCancelled(val requestId: String) : ChatStreamEvent

    /**
     * `session.info`：会话运行时状态（模型 / 推理强度 / fast 档位…）。
     *
     * 模型切换（`config.set`）成功后后端就发这个事件，所以它是 UI 显示当前模型的**唯一真相源** ——
     * 不要在切换请求的返回里回读模型名。
     */
    data class SessionInfoChanged(
        val model: String?,
        val provider: String?,
        val reasoningEffort: String?,
    ) : ChatStreamEvent
}

fun GatewayEvent.toChatStreamEvent(): ChatStreamEvent {
    val payload = payload as? JsonObject
    return when (type) {
        GatewayEventTypes.MESSAGE_START -> ChatStreamEvent.TurnStarted

        GatewayEventTypes.MESSAGE_DELTA -> ChatStreamEvent.Delta(payload.text("text"))

        GatewayEventTypes.MESSAGE_INTERIM -> ChatStreamEvent.Interim(
            text = payload.text("text"),
            alreadyStreamed = (payload?.get("already_streamed") as? JsonPrimitive)?.booleanOrNull == true,
        )

        GatewayEventTypes.REASONING_DELTA -> ChatStreamEvent.ReasoningDelta(payload.text("text"))

        GatewayEventTypes.MESSAGE_COMPLETE -> {
            val status = payload?.stringOrNull("status")
            ChatStreamEvent.Completed(
                text = payload?.get("text").toPlainText(),
                status = status,
                failed = status == STATUS_ERROR,
                error = payload?.stringOrNull("error") ?: payload?.stringOrNull("failure_reason"),
            )
        }

        GatewayEventTypes.TOOL_START -> ChatStreamEvent.ToolStarted(
            toolId = payload?.stringOrNull("tool_id").orEmpty(),
            name = payload?.stringOrNull("name").orEmpty().ifBlank { "tool" },
            label = payload?.labels()?.firstOrNull(),
            arguments = payload?.get("args")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
        )

        GatewayEventTypes.TOOL_COMPLETE -> {
            val resultText = payload?.stringOrNull("result_text")
                ?: payload?.get("result")?.toString()?.takeIf { it != "null" }
            ChatStreamEvent.ToolCompleted(
                toolId = payload?.stringOrNull("tool_id").orEmpty(),
                name = payload?.stringOrNull("name").orEmpty().ifBlank { "tool" },
                result = resultText,
                failed = payload?.stringOrNull("summary")?.let(::looksLikeFailure) == true ||
                    resultText?.let(::looksLikeFailure) == true,
            )
        }

        GatewayEventTypes.SESSION_USAGE -> ChatStreamEvent.UsageUpdated(
            (payload?.get("usage") as? JsonObject)
                ?.get("total")
                ?.let { (it as? JsonPrimitive)?.intOrNull },
        )

        GatewayEventTypes.SESSION_TITLE -> ChatStreamEvent.TitleChanged(
            payload?.stringOrNull("title").orEmpty(),
        )

        GatewayEventTypes.SESSION_INFO -> ChatStreamEvent.SessionInfoChanged(
            model = payload?.stringOrNull("model"),
            provider = payload?.stringOrNull("provider"),
            reasoningEffort = payload?.stringOrNull("reasoning_effort"),
        )

        GatewayEventTypes.ERROR -> ChatStreamEvent.Failure(payload.text("message"))

        GatewayEventTypes.REQUEST_CANCEL -> {
            val method = payload.stringOrNull("method")
            val requestId = payload.stringOrNull("id").orEmpty()
            when {
                requestId.isBlank() -> ChatStreamEvent.Ignored
                method == APPROVAL_REQUEST_METHOD ->
                    ChatStreamEvent.ApprovalCancelled(requestId, payload.stringOrNull("reason"))
                method == CLARIFY_REQUEST_METHOD -> ChatStreamEvent.ClarifyCancelled(requestId)
                else -> ChatStreamEvent.Ignored
            }
        }

        else -> ChatStreamEvent.Ignored
    }
}

/**
 * server request / `approval.pending` 里的审批 payload → 领域对象。
 *
 * `request_id` 是唯一必需的键：没有它的卡片应答不了，直接丢弃比留个坏卡片好。
 */
internal fun JsonObject.toPendingApproval(fromRequest: Boolean): PendingApproval? {
    val requestId = stringOrNull("request_id")?.takeIf { it.isNotBlank() } ?: return null
    return PendingApproval(
        requestId = requestId,
        command = stringOrNull("command").orEmpty(),
        description = stringOrNull("description").orEmpty(),
        choices = (this["choices"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        runtimeSessionId = stringOrNull("session_id")?.takeIf { it.isNotBlank() },
        fromRequest = fromRequest,
    )
}

/** server→client 审批请求的方法名（`tui_gateway/contracts/server_requests.py`）。 */
internal const val APPROVAL_REQUEST_METHOD = "approval"

/** server→client `clarify` 请求的方法名。 */
internal const val CLARIFY_REQUEST_METHOD = "clarify"

/**
 * server request / 事件里的 clarify payload → 领域对象。
 *
 * 单问（`question` 或 `questions` 只有一项）与批量共用 [PendingClarify]；
 * 两项都没有题干就丢弃 —— 空问题卡片无法作答。
 */
internal fun JsonObject.toPendingClarify(frameId: String): PendingClarify? {
    val requestId = stringOrNull("request_id")?.takeIf { it.isNotBlank() } ?: frameId
    val batch = this["questions"] as? JsonArray
    val questions = buildList {
        if (batch != null && batch.isNotEmpty()) {
            batch.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val text = obj.stringOrNull("question")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                add(
                    ClarifyQuestion(
                        qid = obj.stringOrNull("qid")?.takeIf { it.isNotBlank() } ?: "q$index",
                        question = text,
                        choices = obj.choiceList(),
                        multiSelect = (obj["multi_select"] as? JsonPrimitive)?.booleanOrNull == true,
                    ),
                )
            }
        } else {
            val text = stringOrNull("question")?.takeIf { it.isNotBlank() } ?: return null
            add(
                ClarifyQuestion(
                    qid = "q0",
                    question = text,
                    choices = this@toPendingClarify.choiceList(),
                    multiSelect = (this@toPendingClarify["multi_select"] as? JsonPrimitive)?.booleanOrNull == true,
                ),
            )
        }
    }
    if (questions.isEmpty()) return null
    return PendingClarify(
        requestId = requestId,
        frameId = frameId,
        questions = questions,
        runtimeSessionId = stringOrNull("session_id")?.takeIf { it.isNotBlank() }
            ?: stringOrNull("gateway_session_id")?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.choiceList(): List<String> =
    ((this["choices"] as? JsonArray) ?: JsonArray(emptyList()))
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

private const val STATUS_ERROR = "error"

private fun JsonObject?.text(key: String): String = this?.get(key).toPlainText()

private fun JsonObject?.stringOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.labels(): List<String> =
    ((this["labels"] as? JsonArray) ?: JsonArray(emptyList()))
        .mapNotNull { label -> (label as? JsonObject)?.stringOrNull("text") }
        .filter { it.isNotBlank() }
