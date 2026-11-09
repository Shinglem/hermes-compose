package dev.shinglem.hermes.feature.model

import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.SessionFence
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.mutateSession
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 模型切换的结果（`session.mutate {operation:"model"}` 的回执子集）。
 *
 * - `confirmRequired`：目标模型被选择守卫拦下（价格 / 数据策略 / 大上下文），需要把
 *   [confirmToken] **原样**放回 `payload.confirm` 重发 —— 后端只认它下发的那个 token
 *   （绑定了目标、原模型与 revision），传 `true` 或旧 token 只会再被拒一次。
 * - `deferred`：正在生成中，后端把切换排到下一轮开始（此时**不要**立刻回读，等 `session.info`）
 */
data class ModelSwitchOutcome(
    val value: String?,
    val warning: String?,
    val confirmRequired: Boolean,
    val confirmMessage: String?,
    /** `confirmation_required` 回执里的 token，确认时必须原样带回。 */
    val confirmToken: String? = null,
    val deferred: Boolean,
    /** `session.mutate` 回执里的新鲜 fence，调用方要写回自己的 revision/generation。 */
    val revision: Int? = null,
    val executionGeneration: Int? = null,
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
     * 0.23.0 的 canonical gateway 对 `config.set` 直接回 `4001 use_session_mutation_model`，
     * 明确要求改走 `session.mutate {operation:"model", payload:{model, provider}}` ——
     * 那条路要 `expected_revision` + `expected_generation`（`model` 属于 `_POLICY` 操作）。
     */
    suspend fun setModel(
        profile: String?,
        runtimeSessionId: String,
        provider: String,
        model: String,
        revision: Int?,
        executionGeneration: Int?,
        confirmToken: String? = null,
    ): ModelSwitchOutcome {
        val payload = buildJsonObject {
            put("model", model)
            if (provider.isNotBlank()) put("provider", provider)
            // 守卫确认是 **token 交换**，不是布尔：后端把目标/原模型/revision 都绑进这个 token。
            confirmToken?.takeIf { it.isNotBlank() }?.let { put("confirm", it) }
        }
        val receipt = gateway.mutateSession(
            sessionId = runtimeSessionId,
            profile = profile,
            operation = "model",
            payload = payload,
            fence = SessionFence(revision = revision, generation = executionGeneration),
        )
        val result = receipt.result as? JsonObject ?: JsonObject(emptyMap())
        return ModelSwitchOutcome(
            value = result.stringOrNull("model") ?: result.stringOrNull("value"),
            warning = result.stringOrNull("warning")?.takeIf { it.isNotBlank() },
            confirmRequired = (result["confirm_required"] as? JsonPrimitive)?.booleanOrNull == true,
            confirmMessage = result.stringOrNull("confirm_message")?.takeIf { it.isNotBlank() },
            confirmToken = result.stringOrNull("confirm")?.takeIf { it.isNotBlank() },
            deferred = (result["deferred"] as? JsonPrimitive)?.booleanOrNull == true,
            revision = receipt.fence.revision,
            executionGeneration = receipt.fence.generation,
        )
    }

    /**
     * 切当前会话的推理强度。
     *
     * 与模型一样走 `session.mutate {operation:"reasoning", payload:{effort}}`：
     * `reasoning` 也是 `_POLICY` 操作，要带 `expected_generation`。
     */
    suspend fun setReasoning(
        profile: String?,
        runtimeSessionId: String,
        effort: String,
        revision: Int?,
        executionGeneration: Int?,
    ): ModelSwitchOutcome {
        val receipt = gateway.mutateSession(
            sessionId = runtimeSessionId,
            profile = profile,
            operation = "reasoning",
            payload = buildJsonObject { put("effort", effort) },
            fence = SessionFence(revision = revision, generation = executionGeneration),
        )
        val result = receipt.result as? JsonObject ?: JsonObject(emptyMap())
        return ModelSwitchOutcome(
            value = result.stringOrNull("effort") ?: effort,
            warning = result.stringOrNull("warning")?.takeIf { it.isNotBlank() },
            confirmRequired = false,
            confirmMessage = null,
            deferred = (result["deferred"] as? JsonPrimitive)?.booleanOrNull == true,
            revision = receipt.fence.revision,
            executionGeneration = receipt.fence.generation,
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
