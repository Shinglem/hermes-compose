package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.contract.SessionCreateResultDto
import dev.shinglem.hermes.core.contract.SessionListResultDto
import dev.shinglem.hermes.core.contract.SessionListRowDto
import dev.shinglem.hermes.core.contract.SessionSearchResponseDto
import dev.shinglem.hermes.core.contract.SessionSearchResultDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
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
import kotlinx.serialization.json.JsonObject
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
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("limit", limit)
        }
        val element = try {
            gateway.call(RpcMethods.SESSION_LIST, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_LIST)
        }
        val dto = RpcJson.json.decodeFromJsonElement(SessionListResultDto.serializer(), element)
        return dto.sessions.map(SessionListRowDto::toSummary)
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
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            put("source", "desktop")
            // 草稿态在 composer 里选好的模型：`session.create` 的这三个参数是 per-session
            // override，不写 config.yaml（见 tui_gateway/methods_session.py::_create_overrides）。
            provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
        }
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

    /**
     * 给**还没落库**的会话改标题（刚 `session.create` 出来的那条）。
     *
     * 它在后端内存里是 live 的，所以 RPC `session.title` 可用，标题会排队到首轮生效；
     * REST `PATCH` 此时会 404（库里还没有这一行）。
     */
    suspend fun renameLiveSession(profile: String?, sessionId: String, title: String) {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("session_id", sessionId)
            put("title", title)
        }
        try {
            gateway.call(RpcMethods.SESSION_TITLE, params, Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_TITLE)
        }
    }

    /**
     * 归档 / 取消归档。
     *
     * 与 `session.title` / `session.delete` 不同，`session.archive` 不是 session-scoped
     * （后端注释：live runtime id 优先，其次 stored id / key），冷会话也能直接归档，不需要先点开。
     * 归档是**软隐藏**：会话行不再出现在 `session.list` 里，消息仍在。
     */
    suspend fun setArchived(profile: String?, sessionId: String, archived: Boolean) {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("session_id", sessionId)
            put("archived", archived)
        }
        try {
            gateway.call(RpcMethods.SESSION_ARCHIVE, params, Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_ARCHIVE)
        }
    }

    /** 删除会话（含 transcript），走 RPC：由客户端会话作用域处理，需该会话在内存里（先点开）。 */
    suspend fun deleteSession(profile: String?, sessionId: String) {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("session_id", sessionId)
        }
        try {
            gateway.call(RpcMethods.SESSION_DELETE, params, Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_DELETE)
        }
    }

    /**
     * 复制为新会话（整份历史克隆，原会话不动）。
     *
     * 走 `session.branch_stored`：它内部就是 `session.create + copy_parent_history`，
     * **父会话不必是活跃会话** —— 这是会话行菜单能直接用的前提。
     *
     * ⚠️ 父会话 id 的字段名是 `parent_session_id`（不是 `session_id`）：
     * 后端缺这个字段会直接 `4008 parent_session_id is required when copying parent history`。
     */
    suspend fun duplicateSession(profile: String?, sessionId: String): String {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("parent_session_id", sessionId)
            put("source", "desktop")
        }
        val element = try {
            gateway.call(RpcMethods.SESSION_BRANCH_STORED, params, Timeouts.WRITE_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_BRANCH_STORED)
        }
        val dto = RpcJson.json.decodeFromJsonElement(SessionCreateResultDto.serializer(), element)
        return dto.storedSessionId?.takeIf { it.isNotBlank() } ?: dto.sessionId
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
        const val DEFAULT_LIMIT = 200

        /** 置顶集合的取样上限：与 `GET /api/sessions` 的 `limit` 上限一致。 */
        const val PIN_LIMIT = 200
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
