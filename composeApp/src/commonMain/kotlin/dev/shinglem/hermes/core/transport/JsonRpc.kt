package dev.shinglem.hermes.core.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** JSON-RPC 2.0 帧编解码。契约细节见 `.design/草案/08-data-contract.md` §2.1。 */
object RpcJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        // 后端有些字段会返回 null 而不是省略（例如 skill 的 category）。
        // DTO 全都有默认值，所以把 null 当作"用默认值"，别让一个 null 崩掉整个列表。
        coerceInputValues = true
    }
}

data class RpcErrorDetail(val code: Int, val message: String, val data: JsonElement? = null)

sealed interface IncomingFrame {
    data class Response(
        val id: JsonElement,
        val result: JsonElement?,
        val error: RpcErrorDetail?,
    ) : IncomingFrame

    data class Event(val event: GatewayEvent) : IncomingFrame

    /** server → client 请求：必须应答，否则服务端会等超时。 */
    data class ServerRequest(
        val id: JsonElement,
        val method: String,
        val params: JsonElement?,
    ) : IncomingFrame

    data class Malformed(val detail: String) : IncomingFrame
}

object JsonRpc {
    const val METHOD_NOT_FOUND = -32601
    const val INTERNAL_ERROR = -32603

    fun encodeRequest(id: Long, method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", JsonPrimitive(id))
        put("method", JsonPrimitive(method))
        put("params", params)
    }.toString()

    fun encodeResult(id: JsonElement, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("result", result)
    }.toString()

    fun encodeError(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            },
        )
    }.toString()

    fun parse(text: String): IncomingFrame {
        val root = runCatching { RpcJson.json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return IncomingFrame.Malformed("frame is not a JSON object")
        val id = root["id"]
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val params = root["params"]

        return when {
            method == null && id != null -> IncomingFrame.Response(
                id = id,
                result = root["result"]?.takeUnless { it is JsonNull },
                error = parseError(root["error"]),
            )

            method == "event" -> IncomingFrame.Event(parseEvent(params))

            method != null && id != null -> IncomingFrame.ServerRequest(id = id, method = method, params = params)

            else -> IncomingFrame.Malformed("frame has neither id nor method")
        }
    }

    private fun parseError(element: JsonElement?): RpcErrorDetail? {
        val obj = element as? JsonObject ?: return null
        val code = (obj["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // `data` 里带着 fence 的新鲜值（`revision` / `execution_generation`）与
        // `fields`（闭集契约拒绝时指出哪个键不合法），丢了就只能盲试。
        return RpcErrorDetail(code, message, obj["data"]?.takeUnless { it is JsonNull })
    }

    private fun parseEvent(params: JsonElement?): GatewayEvent {
        val obj = params as? JsonObject ?: return GatewayEvent(type = "")
        return GatewayEvent(
            type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            sessionId = (obj["session_id"] as? JsonPrimitive)?.contentOrNull,
            seq = (obj["seq"] as? JsonPrimitive)?.longOrNull,
            payload = obj["payload"],
            executionGeneration = (obj["execution_generation"] as? JsonPrimitive)?.intOrNull,
        )
    }
}
