package dev.shinglem.hermes.component.subagent

import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.contract.SubagentListDto
import dev.shinglem.hermes.core.contract.SubagentTailDto
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * [SubagentGateway] 的 RPC 实现。
 *
 * `runtime()` 是构造参数而不是接口方法：它需要"当前选中会话"这份 UI 状态，
 * 而那属于 `ChatComponent` / `SessionListViewModel`。S8 收尾时改成依赖 Component。
 */
class RpcSubagentGateway(
    private val gateway: GatewayClient,
    private val runtimeMarker: () -> RuntimeRef?,
) : SubagentGateway {

    override val events: Flow<dev.shinglem.hermes.core.transport.GatewayEvent> =
        gateway.events.filter { it.type in GatewayEventTypes.SUBAGENT_EVENTS }

    override suspend fun list(sessionId: String, profile: String?): SubagentListDto {
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val result = gateway.call(RpcMethods.SUBAGENT_LIST, params, Timeouts.DEFAULT_MS)
        return RpcJson.json.decodeFromJsonElement(SubagentListDto.serializer(), result)
    }

    override suspend fun tail(sessionId: String, profile: String?, subagentId: String): SubagentTailDto {
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("subagent_id", subagentId)
        }
        val result = gateway.call(RpcMethods.SUBAGENT_TAIL, params, Timeouts.DEFAULT_MS)
        return RpcJson.json.decodeFromJsonElement(SubagentTailDto.serializer(), result)
    }

    override suspend fun steer(
        sessionId: String,
        profile: String?,
        subagentId: String,
        text: String,
    ): String? {
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("subagent_id", subagentId)
            put("text", text)
        }
        val result = gateway.call(RpcMethods.SUBAGENT_STEER, params, Timeouts.DEFAULT_MS)
        return (result as? JsonObject)?.stringOrNull("status")
    }

    override suspend fun interrupt(sessionId: String, profile: String?, subagentId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("subagent_id", subagentId)
        }
        gateway.call(RpcMethods.SUBAGENT_INTERRUPT, params, Timeouts.DEFAULT_MS)
    }

    override fun runtime(): RuntimeRef? = runtimeMarker()
}

/** 与 `ChatRepository` 里的同名私有函数一致（那边是 private，无法复用）。 */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content