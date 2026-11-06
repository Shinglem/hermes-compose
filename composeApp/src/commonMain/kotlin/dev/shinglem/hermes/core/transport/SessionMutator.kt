package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.error.technicalSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.random.Random

/**
 * 会话写入（`session.mutate`）与控制类调用（interrupt / steer / approval / clarify）的**唯一实现**。
 *
 * 0.23.0 的 canonical gateway 给这两类调用加了 fence：
 *
 * | 调用 | 需要的 fence | 过期时的 `data` |
 * |---|---|---|
 * | `session.mutate` | `expected_revision`（必填）+ 部分操作要 `expected_generation` | `revision` / `execution_generation` |
 * | `session.interrupt` / `session.steer` | `execution_generation`（必填） | `execution_generation` |
 * | `approval.respond` / `clarify.respond` | `execution_generation` + `prompt_id` | `execution_generation` |
 *
 * 两者都遵循同一条规则：**拒绝里带回新鲜值 → 用同一个 request_id 重试一次**（幂等）。
 * 以前这段逻辑在 ChatRepository 与 SessionRepository 各写一份，改一处漏一处；
 * 现在只有这里一份，fence 的取值由调用方从 [SessionFence] 里取。
 */

/** 一个会话的 fence：`revision` 来自 resume 回执 / 事件 / 拒绝回执，`generation` 同理。 */
data class SessionFence(val revision: Int? = null, val generation: Int? = null) {
    companion object {
        fun of(element: JsonElement?): SessionFence {
            val obj = element as? JsonObject ?: return SessionFence()
            return SessionFence(
                revision = (obj["revision"] as? JsonPrimitive)?.intOrNull,
                generation = (obj["execution_generation"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }
}

/** 写入回执：新 fence + 原始结果（`session.mutate` 的 projection 就在里面）。 */
data class MutationReceipt(val fence: SessionFence, val result: JsonElement)

private fun newRequestId(prefix: String): String =
    "hdc-$prefix-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(100_000, 999_999)}"

/**
 * `session.mutate`：参数集**精确匹配**（`gateway/session_mutations.py::_FIELDS`）。
 * `profile` 会先用于跨 profile 路由、再被 `_PROFILE_IMPLICIT` 剥离，所以必须带上。
 */
suspend fun GatewayClient.mutateSession(
    sessionId: String,
    profile: String?,
    operation: String,
    payload: JsonObject,
    fence: SessionFence,
    requiresGeneration: Boolean = false,
    timeoutMs: Long = Timeouts.WRITE_MS,
): MutationReceipt {
    val requestId = newRequestId("m")
    var current = fence
    var attempt = 0
    while (true) {
        val revision = current.revision
            ?: throw AppError.Protocol("session.mutate/$operation 需要 expected_revision（先 resume 该会话）")
        if (requiresGeneration && current.generation == null) {
            throw AppError.Protocol("session.mutate/$operation 需要 expected_generation（先 resume 该会话）")
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("request_id", requestId)
            put("expected_revision", revision)
            current.generation?.let { put("expected_generation", it) }
            put("operation", operation)
            put("payload", payload)
        }
        try {
            val result = call(RpcMethods.SESSION_MUTATE, params, timeoutMs)
            // 写入成功即推进了一代：把回执里的新 fence 交回调用方保存。
            return MutationReceipt(SessionFence.of(result).let { fresh ->
                SessionFence(
                    revision = fresh.revision ?: revision,
                    generation = fresh.generation ?: current.generation,
                )
            }, result)
        } catch (refused: AppError.Server) {
            val data = refused.data as? JsonObject
            val freshRevision = (data?.get("revision") as? JsonPrimitive)?.intOrNull
            val freshGeneration = (data?.get("execution_generation") as? JsonPrimitive)?.intOrNull
            if (attempt++ >= 1 || (freshRevision == null && freshGeneration == null)) {
                throw ErrorMapper.from(refused, RpcMethods.SESSION_MUTATE)
            }
            current = SessionFence(
                revision = freshRevision ?: current.revision,
                generation = freshGeneration ?: current.generation,
            )
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_MUTATE)
        }
    }
}

/**
 * 控制类调用（`session.interrupt` / `session.steer` / `approval.respond` / `clarify.respond`）。
 *
 * 共同点：`execution_generation` 必填，且每一轮都会推进它 —— 事件流会同步本地值，
 * 这里再把"事件还没到"的窗口用拒绝里的 `data.execution_generation` 兜一次。
 */
suspend fun GatewayClient.sessionControl(
    method: String,
    sessionId: String,
    profile: String?,
    fence: SessionFence,
    timeoutMs: Long = Timeouts.DEFAULT_MS,
    extra: JsonObject = JsonObject(emptyMap()),
): MutationReceipt {
    var generation = fence.generation
        ?: throw AppError.Protocol("$method 需要 execution_generation（先 resume 该会话）")
    var attempt = 0
    while (true) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("execution_generation", generation)
            extra.forEach { (key, value) -> put(key, value) }
        }
        try {
            val result = call(method, params, timeoutMs)
            return MutationReceipt(SessionFence(revision = fence.revision, generation = generation), result)
        } catch (refused: AppError.Server) {
            val data = refused.data as? JsonObject
            val fresh = (data?.get("execution_generation") as? JsonPrimitive)?.intOrNull
            if (attempt++ >= 1 || fresh == null) throw ErrorMapper.from(refused, method)
            generation = fresh
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, method)
        }
    }
}

/** 供日志与诊断页使用。 */
fun MutationReceipt.summary(): String = "fence=${fence.revision}/${fence.generation} ${result.technicalSummaryOrEmpty()}"

private fun JsonElement.technicalSummaryOrEmpty(): String =
    runCatching { AppError.Unknown(toString()).technicalSummary() }.getOrDefault("")
