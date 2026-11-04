package dev.shinglem.hermes.feature.model

import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * 模型切换的结果（`config.set` 的 `ConfigSetResult` 子集）。
 *
 * - `confirmRequired`：目标模型价格偏高，需要带 `confirm_expensive_model=true` 重发
 * - `deferred`：正在生成中，后端把切换排到下一轮开始（此时**不要**立刻回读，等 `session.info`）
 */
data class ModelSwitchOutcome(
    val value: String?,
    val warning: String?,
    val confirmRequired: Boolean,
    val confirmMessage: String?,
    val deferred: Boolean,
)

/** `/api/model/info` 的相关字段。 */
data class ModelInfo(
    val model: String,
    val provider: String?,
)

/** 模型域的数据入口：RPC 走目录与切换，REST 走轻量的 `model/info`。 */
class ModelRepository(
    private val gateway: GatewayClient,
    private val rest: RestClient,
) {

    /**
     * 当前 profile 配置的模型（REST `/api/model/info`，不需要超时补偿：后端自己 bounded）。
     *
     * 用途：打开会话时先填上 pill 的初始值。会话级 override 只能靠 `session.info` /
     * `session.resume` 拿到，这里给的是 profile 默认 —— 多数情况下两者相同。
     */
    suspend fun current(profile: String?): ModelInfo? {
        val query = profile?.takeIf { it.isNotBlank() }?.let { "?profile=${it.encodeURLParameter()}" }.orEmpty()
        val element = try {
            rest.get("/api/model/info$query", Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, "model/info")
        }
        val dto = RpcJson.json.decodeFromJsonElement(ModelInfoDto.serializer(), element)
        val model = dto.model?.takeIf { it.isNotBlank() } ?: return null
        return ModelInfo(
            model = model,
            provider = dto.provider?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 拉 provider + 模型目录。
     *
     * `refresh=false` 走后端 1h 磁盘缓存（正常打开不要传 true，否则会把所有 provider 探一遍，
     * 卡十几秒）；`explicit_only` 只列用户显式配置过的 provider。
     */
    suspend fun options(profile: String?, refresh: Boolean = false): ModelCatalog {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            if (refresh) put("refresh", true)
        }
        val element = try {
            gateway.call(RpcMethods.MODEL_OPTIONS, params, Timeouts.STARTUP_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.MODEL_OPTIONS)
        }
        val dto = RpcJson.json.decodeFromJsonElement(ModelOptionsDto.serializer(), element)
        return dto.toCatalog()
    }

    /**
     * 切当前会话的模型。
     *
     * `value` 的格式照官方桌面端：`"<model> --provider <provider>"`。
     * **不加 `--session`** —— 后端 `model.persist_switch_by_default` 默认 false，
     * 普通选择本来就只影响当前会话；加 `--session` 反而会覆盖用户显式打开的"记住选择"配置。
     */
    suspend fun setModel(
        profile: String?,
        runtimeSessionId: String,
        provider: String,
        model: String,
        confirmExpensive: Boolean = false,
    ): ModelSwitchOutcome {
        val value = buildString {
            append(model)
            if (provider.isNotBlank()) append(" --provider ").append(provider)
        }
        val result = configSet(
            profile = profile,
            sessionId = runtimeSessionId,
            key = KEY_MODEL,
            value = value,
            extra = if (confirmExpensive) mapOf("confirm_expensive_model" to JsonPrimitive(true)) else emptyMap(),
        )
        return ModelSwitchOutcome(
            value = result.stringOrNull("value"),
            warning = result.stringOrNull("warning")?.takeIf { it.isNotBlank() },
            confirmRequired = (result["confirm_required"] as? JsonPrimitive)?.booleanOrNull == true,
            confirmMessage = result.stringOrNull("confirm_message")?.takeIf { it.isNotBlank() },
            deferred = (result["deferred"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /**
     * 切当前会话的推理强度。
     *
     * `scope="session"` 与官方一致：菜单选择不得改写全局 `agent.reasoning_effort`。
     */
    suspend fun setReasoning(profile: String?, runtimeSessionId: String, effort: String) {
        configSet(
            profile = profile,
            sessionId = runtimeSessionId,
            key = KEY_REASONING,
            value = effort,
            extra = mapOf("scope" to JsonPrimitive("session")),
        )
    }

    private suspend fun configSet(
        profile: String?,
        sessionId: String,
        key: String,
        value: String,
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): JsonObject {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("session_id", sessionId)
            put("key", key)
            put("value", value)
            extra.forEach { (k, v) -> put(k, v) }
        }
        val element = try {
            gateway.call(RpcMethods.CONFIG_SET, params, Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.CONFIG_SET)
        }
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_REASONING = "reasoning"
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
