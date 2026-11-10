package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `GET /api/sessions/{id}/messages` 的响应（`hermes_cli/web_routers/sessions.py::get_session_messages`）。
 *
 * 读历史走 REST 而不是 `session.resume`：resume 会注册 live session 并预热 agent，
 * 而"只是看一眼"不需要。字段名逐一对应落库行。
 */
@Serializable
data class TranscriptPageDto(
    @SerialName("session_id") val sessionId: String = "",
    val profile: String? = null,
    val messages: List<TranscriptRowDto> = emptyList(),
    val pagination: TranscriptPaginationDto = TranscriptPaginationDto(),
)

@Serializable
data class TranscriptPaginationDto(
    val limit: Int = 0,
    val offset: Int = 0,
    val order: String = "latest",
    val returned: Int = 0,
)

@Serializable
data class TranscriptRowDto(
    val id: String = "",
    val role: String = "",
    val content: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: Double? = null,
    @SerialName("display_kind") val displayKind: String? = null,
    @SerialName("tool_call_labels") val toolCallLabels: Map<String, List<ToolLabelDto>>? = null,
    /** `stop` / `tool_calls` / `length` / `content_filter` / `incomplete`；异常值要在气泡后提示。 */
    @SerialName("finish_reason") val finishReason: String? = null,
    /**
     * 这一步的思考过程。后端每一步各落一条 assistant 行、各自带 reasoning，
     * 所以重开历史能**按步分段**还原（与官方桌面端一致）。
     * `reasoning_details` / `codex_reasoning_items` 是加密块或结构化项，不显示、不接。
     */
    val reasoning: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ToolCallDto(
    val id: String? = null,
    val type: String? = null,
    val function: ToolFunctionDto? = null,
)

@Serializable
data class ToolFunctionDto(
    val name: String = "",
    val arguments: String = "",
)

/** `tools.tool_labels.ToolLabel`：客户端只渲染 [text]，不解析工具名。 */
@Serializable
data class ToolLabelDto(
    val kind: String? = null,
    val app: String = "",
    val action: String = "",
    val emoji: String = "",
    val text: String = "",
    val name: String = "",
    val preview: String = "",
)

/** 内容单元格：字符串、多模态数组（text / image_url）、或对象。 */
fun JsonElement?.toPlainText(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> mapNotNull { part -> part.partText() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    is JsonObject -> this["text"]?.toPlainText().orEmpty()
}

private fun JsonElement.partText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> {
        when (val type = this["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
            "text", "input_text" -> this["text"]?.toPlainText()
            "image_url", "image", "input_image" -> "[image]"
            else -> if (type == null) this["text"]?.toPlainText() else null
        }
    }

    is JsonArray -> null
}
