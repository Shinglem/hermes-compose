package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.contract.ClientPrompt
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.contract.SessionCreateResultDto
import dev.shinglem.hermes.core.contract.SessionListResultDto
import dev.shinglem.hermes.core.contract.SessionListRowDto
import dev.shinglem.hermes.core.contract.SessionSearchResponseDto
import dev.shinglem.hermes.core.contract.SessionSearchResultDto
import dev.shinglem.hermes.core.contract.SessionSurface
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import io.ktor.http.encodeURLParameter
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 会话列表行（`session.list` 的领域模型）。 */
data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Instant,
    val messageCount: Int,
    val source: String,
) {
    val displayTitle: String
        get() = title.ifBlank { preview.ifBlank { id } }
}

/** `session` 域的数据入口：只碰传输层，不 import 任何 Compose 类型。 */
class SessionRepository(
    private val gateway: GatewayClient,
    private val rest: RestClient,
) {
    suspend fun listSessions(profile: String?, limit: Int = DEFAULT_LIMIT): List<SessionSummary> {
        // 走 REST 而不是 RPC `session.list`：0.23.0 的 RPC 默认 `scope=live`（只给运行中的会话），
        // 历史会话要 `scope=stored`；官方 Desktop 也是直接读 `/api/sessions`（见 RestClient 注释）。
        //
        // `profile` 必须**显式**给：不带它时后端只返回很少的几行（实测 default 下 6 vs 77），
        // 而 UI 的 `null` 语义就是默认 profile。
        val resolved = profile?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE
        val sessions = rest.listSessions(profile = resolved, limit = limit)
        // 留一条痕迹：会话列表为空是最容易误判成「数据丢了」的现象，
        // 有这行就能立刻分清「后端没给」还是「解析丢了」。
        Log.info("sessions loaded: profile=$resolved count=${sessions.size} limit=$limit")
        return sessions
    }

    /**
     * 新建一个空会话，返回**落库的**会话 id（列表与 history 都用它）。
     *
     * 后端在第一条 prompt 之前不会给这个会话生成列表行（`sessions.py` 的
     * "a DB row appears on the first prompt unless seeded"），所以这里返回的 id
     * 在 `session.list` 里暂时查不到 —— 属正常，选中态不依赖列表。
     */
    suspend fun createSession(
        profile: String?,
        title: String? = null,
        provider: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
    ): String {
        val params = createSessionParams(
            profile = profile,
            title = title,
            provider = provider,
            model = model,
            reasoningEffort = reasoningEffort,
        )
        val element = try {
            gateway.call(RpcMethods.SESSION_CREATE, params, Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_CREATE)
        }
        val dto = RpcJson.json.decodeFromJsonElement(SessionCreateResultDto.serializer(), element)
        return dto.storedSessionId?.takeIf { it.isNotBlank() } ?: dto.sessionId
    }

    /**
     * 改会话标题。
     *
     * 两个通道的适用面不同（实测）：
     * - 已落库的会话 → REST `PATCH /api/sessions/{id}`（官方桌面端的 sidebar 通道）；
     * - RPC `session.title` 要求会话是 **live runtime**，用 stored id 直接调会 `4001 session not found`。
     *
     * REST 这条路还要过 mutation fence：`request_id` + `expected_revision` 缺一个就
     * `409 client_upgrade_required`（后端把它当作"旧客户端"），所以先读 snapshot 再写。
     */
    suspend fun renameSession(profile: String?, sessionId: String, title: String) =
        mutateSidebar(profile, sessionId, buildJsonObject { put("title", title) })

    /** `session.mutate` 的会话级写入（带 profile 以便跨 profile 路由，见 `_PROFILE_IMPLICIT`）。 */
    private suspend fun mutateSession(
        sessionId: String,
        operation: String,
        payload: JsonObject,
        revision: Int?,
        profile: String?,
        executionGeneration: Int? = null,
        timeoutMs: Long = Timeouts.WRITE_MS,
    ): JsonElement {
        if (revision == null) throw AppError.Protocol("session.mutate 需要 expected_revision")
        val params = buildJsonObject {
            put("session_id", sessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("request_id", newRequestId())
            put("expected_revision", revision)
            executionGeneration?.let { put("expected_generation", it) }
            put("operation", operation)
            put("payload", payload)
        }
        return try {
            gateway.call(RpcMethods.SESSION_MUTATE, params, timeoutMs)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_MUTATE)
        }
    }

    /**
     * 给**还没落库**的会话改标题（刚 `session.create` 出来的那条）。
     *
     * 它在后端内存里是 live 的，REST `PATCH` 会 404（库里还没有这一行），所以走
     * `session.mutate {operation:"rename"}`。新会话的 revision 从 0 起，冲突时后端会在
     * 拒绝里带回新鲜的 `data.revision` —— 用它重试一次（同一个 `request_id`，幂等）。
     */
    suspend fun renameLiveSession(sessionId: String, title: String) {
        var revision = 0
        var attempt = 0
        while (true) {
            try {
                mutateSession(
                    sessionId = sessionId,
                    operation = "rename",
                    payload = buildJsonObject { put("title", title) },
                    revision = revision,
                    profile = null,
                )
                return
            } catch (conflict: AppError.Server) {
                val fresh = ((conflict.data as? JsonObject)?.get("revision") as? JsonPrimitive)?.intOrNull
                if (attempt++ >= 1 || fresh == null) {
                    throw ErrorMapper.from(conflict, RpcMethods.SESSION_MUTATE)
                }
                revision = fresh
            }
        }
    }

    /**
     * 归档 / 取消归档。
     *
     * 0.23.0 的 canonical gateway **没有** `session.archive`（-32601），所以统一走
     * REST `PATCH /api/sessions/{id}`（官方 Desktop 的 sidebar 通道，同一条 fence 规则）。
     * 归档是**软隐藏**：会话行不再出现在列表里，消息仍在。
     */
    suspend fun setArchived(profile: String?, sessionId: String, archived: Boolean) =
        mutateSidebar(profile, sessionId, buildJsonObject { put("archived", archived) })

    /**
     * 删除会话（含 transcript）。
     *
     * 走 REST `DELETE /api/sessions/{id}`，但 fence 在 **query string** 上
     * （`request_id` / `expected_revision` / `expected_generation`）——
     * `hermes_cli/web_routers/sessions.py::delete_session_endpoint` 只声明了 query 参数，
     * 放进 body 后端一个都读不到，于是三个全是 `None` → `409 client_upgrade_required`
     * （它的文案是"客户端版本不同步"，极容易误判成版本问题）。
     */
    suspend fun deleteSession(profile: String?, sessionId: String) {
        var attempt = 0
        while (true) {
            val snapshot = mutationSnapshot(profile, sessionId)
                ?: throw AppError.Server(409, "读不到会话的 mutation-snapshot")
            val query = buildString {
                append("/api/sessions/").append(sessionId.encodeURLParameter())
                append("?request_id=").append(newRequestId())
                append("&expected_revision=").append(snapshot.revision)
                snapshot.generation?.let { append("&expected_generation=").append(it) }
                profile?.takeIf { it.isNotBlank() }?.let {
                    append("&profile=").append(it.encodeURLParameter())
                }
            }
            try {
                rest.send(RestMethod.DELETE, query)
                return
            } catch (conflict: AppError.Server) {
                if (attempt++ >= 1) throw ErrorMapper.from(conflict, "sessions.delete")
            } catch (throwable: Throwable) {
                throw ErrorMapper.from(throwable, "sessions.delete")
            }
        }
    }

    /**
     * 复制为新会话（整份历史克隆，原会话不动）。
     *
     * canonical gateway 没有 `session.branch_stored`（-32601），改走
     * `session.mutate {operation:"branch", payload:{}}` —— 不带 `through_message_id`
     * 就是整份克隆，且**父会话不必是活跃会话**（这是会话行菜单能直接用的前提）。
     */
    suspend fun duplicateSession(profile: String?, sessionId: String): String {
        val snapshot = mutationSnapshot(profile, sessionId)
            ?: throw AppError.Server(409, "读不到会话的 mutation-snapshot")
        val result = mutateSession(
            sessionId = sessionId,
            operation = "branch",
            payload = JsonObject(emptyMap()),
            revision = snapshot.revision,
            profile = profile,
            executionGeneration = snapshot.generation,
            timeoutMs = Timeouts.WRITE_MS,
        )
        val obj = result as? JsonObject ?: throw AppError.Protocol("session.mutate returned no object")
        return obj["branched_session_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: obj["stored_session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AppError.Protocol("session.mutate/branch returned no session id")
    }

    /** 后端 `/api/sessions/search` 的原始结果（内容检索，命中带 snippet）。 */
    suspend fun searchSessions(profile: String?, query: String): List<SessionSearchResultDto> {
        if (query.isBlank()) return emptyList()
        val path = withProfileQuery("/api/sessions/search?q=${query.encodeURLParameter()}", profile)
        val element = try {
            rest.get(path, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, "sessions.search")
        }
        return RpcJson.json.decodeFromJsonElement(SessionSearchResponseDto.serializer(), element).results
    }

    /**
     * 当前活跃（live）会话的 stored id 集合。
     *
     * 只用来在列表行上画运行态点；拿不到就返回空集合（不影响列表本身）。
     */
    suspend fun activeSessionIds(profile: String?): Set<String> {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val element = runCatching {
            gateway.call(RpcMethods.SESSION_ACTIVE_LIST, params, Timeouts.DEFAULT_MS)
        }.getOrNull() ?: return emptySet()
        val obj = element as? JsonObject ?: return emptySet()
        val rows = obj["sessions"] as? kotlinx.serialization.json.JsonArray ?: return emptySet()
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val key = (item["session_key"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val id = (item["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            key ?: id
        }.toSet()
    }

    /**
     * sidebar 写（标题 / 置顶）统一走这里：先读 `mutation-snapshot` 拿 `runtime_revision`，
     * 再带上 `request_id` + `expected_revision`（有 generation 时一并带上）提交。
     *
     * 409（别人改过、revision 变了）时重取一次 revision 再试一遍，仍失败才算失败。
     */
    private suspend fun mutateSidebar(profile: String?, sessionId: String, fields: JsonObject) {
        val path = withProfileQuery("/api/sessions/$sessionId", profile)
        var attempt = 0
        while (true) {
            val snapshot = mutationSnapshot(profile, sessionId)
                ?: throw AppError.Server(409, "读不到会话的 mutation-snapshot")
            val body = buildJsonObject {
                fields.forEach { (key, value) -> put(key, value) }
                put("request_id", newRequestId())
                put("expected_revision", snapshot.revision)
                snapshot.generation?.let { put("expected_generation", it) }
            }
            try {
                rest.send(RestMethod.PATCH, path, body)
                return
            } catch (conflict: AppError.Server) {
                if (attempt++ >= 1) throw ErrorMapper.from(conflict, "session.mutate")
            } catch (throwable: Throwable) {
                throw ErrorMapper.from(throwable, "session.mutate")
            }
        }
    }

    /** `GET /api/sessions/{id}/mutation-snapshot`：fence 的版本号（`runtime_revision` / `runtime_generation`）。 */
    private suspend fun mutationSnapshot(profile: String?, sessionId: String): MutationSnapshot? = runCatching {
        val element = rest.get(withProfileQuery("/api/sessions/$sessionId/mutation-snapshot", profile))
        val obj = element as? JsonObject ?: return@runCatching null
        val revision = obj["runtime_revision"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
        MutationSnapshot(revision, obj["runtime_generation"]?.jsonPrimitive?.intOrNull)
    }.getOrNull()

    private data class MutationSnapshot(val revision: Int, val generation: Int?)

    /** 请求级幂等 id（后端按它去重 receipt）；本地唯一即可，不需要真 UUID。 */
    private fun newRequestId(): String =
        "hdc-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(100_000, 999_999)}"

    private companion object {
        /** `GET /api/sessions` 的 `limit` 上限就是 100（超过即 422），不要写更大。 */
        const val DEFAULT_LIMIT = 100

        /** `profile = null` 在 UI 里就是「默认档案」；REST 必须显式给这个名字。 */
        const val DEFAULT_PROFILE = "default"

        /** 置顶集合的取样上限：与 `GET /api/sessions` 的 `limit` 上限一致。 */
        const val PIN_LIMIT = 100
    }
}

private fun SessionListRowDto.toSummary(): SessionSummary = SessionSummary(
    id = resolvedId ?: id,
    title = title,
    preview = preview,
    startedAt = TimeFormat.fromEpochSeconds(startedAt),
    messageCount = messageCount,
    source = source,
)

/**
 * `session.create` 的参数（纯函数，便于断言）。
 *
 * 两处「客户端往会话里塞东西」都集中在这里：
 * - `source`：见 [SessionSurface]，决定工具集与系统提示；
 * - `client_env`：见 [ClientPrompt]，把真实的渲染能力告诉 agent。
 *
 * `client_env` **只有 create 接受**（`CanonicalResumeParams` 没有这个字段），所以
 * 「复制为新会话」（`session.mutate {operation:"branch"}`，它的 payload 也没有）拿不到
 * 这段提示词 —— 复制出来的会话会退回后端默认的 tui 描述，属已知限制。
 */
internal fun createSessionParams(
    profile: String?,
    title: String? = null,
    provider: String? = null,
    model: String? = null,
    reasoningEffort: String? = null,
): JsonObject = buildJsonObject {
    profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
    title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
    put("source", SessionSurface.CLIENT)
    // 草稿态在 composer 里选好的模型：`session.create` 的这几个参数是 per-session override，
    // 不写 config.yaml。注意 canonical 契约（`CanonicalCreateParams`）里推理强度的键是
    // **`reasoning`** —— 写 `reasoning_effort` 会被闭集校验判 `4001 invalid_params`。
    provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
    model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
    reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning", it) }
    put(
        "client_env",
        buildJsonObject { put(ClientPrompt.ENV_KEY, ClientPrompt.RENDERING) },
    )
}
