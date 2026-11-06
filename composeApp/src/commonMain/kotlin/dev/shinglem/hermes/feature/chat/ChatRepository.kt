package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.contract.BranchResultDto
import dev.shinglem.hermes.core.contract.ContextBreakdownDto
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.contract.SessionSurface
import dev.shinglem.hermes.core.contract.ToolLabelDto
import dev.shinglem.hermes.core.contract.TranscriptPageDto
import dev.shinglem.hermes.core.contract.TranscriptRowDto
import dev.shinglem.hermes.core.contract.UsageDto
import dev.shinglem.hermes.core.contract.toPlainText
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.time.TimeFormat
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.random.Random

/** 会话历史读取（REST 分页，只读）+ 发送侧（WebSocket RPC）。 */
class ChatRepository(
    private val gateway: GatewayClient,
    private val rest: RestClient,
) {
    /**
     * 读一页历史。`limit` 上限 500（后端钳制）。
     *
     * `order=latest` + `offset` 是"从末尾往前翻"：`offset=500` 拿到倒数第 501–1000 条。
     * 向上加载更早消息就走这条路，不要把 offset 和 `order=oldest` 混用（语义完全相反）。
     */
    suspend fun loadTranscript(
        profile: String?,
        sessionId: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): TranscriptPage {
        val query = buildString {
            append("limit=").append(limit.coerceIn(1, MAX_LIMIT))
            append("&order=latest")
            if (offset > 0) append("&offset=").append(offset)
            profile?.takeIf { it.isNotBlank() }?.let { append("&profile=").append(it.encodeURLParameter()) }
        }
        val element = try {
            rest.get("/api/sessions/${sessionId.encodeURLParameter()}/messages?$query", Timeouts.STARTUP_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, "sessions.messages")
        }
        val dto = RpcJson.json.decodeFromJsonElement(TranscriptPageDto.serializer(), element)
        return TranscriptPage(
            sessionId = dto.sessionId.ifBlank { sessionId },
            rows = dto.messages.map(TranscriptRowDto::toRow),
            pageSize = dto.pagination.limit,
            returned = dto.pagination.returned,
            offset = dto.pagination.offset,
        )
    }

    /**
     * 拉全量历史（导出 / 复制 / 分支共用）。
     *
     * 循环翻页直到某页不足 [MAX_LIMIT]，并用 [MAX_ALL_MESSAGES] 兜底 ——
     * 巨型会话宁可少拿尾部，也不要在导出时把内存打满。
     */
    suspend fun fetchAllMessages(profile: String?, sessionId: String): List<ChatRow> {
        val collected = mutableListOf<ChatRow>()
        var offset = 0
        while (collected.size < MAX_ALL_MESSAGES) {
            val page = loadTranscript(profile, sessionId, LIMIT, offset)
            // 后端返回的是"从末尾往前"的一页，拼回时间顺序要把它插到前面。
            collected.addAll(0, page.rows)
            if (!page.hasMore) break
            offset += page.returned.coerceAtLeast(1)
        }
        return collected
    }

    /**
     * `session.resume`：拿 runtime id（历史已由 REST 读过，不再回传 transcript）。
     *
     * **必须显式传 `source`**，值见 [SessionSurface.CLIENT]：不传的话后端会按
     * `HERMES_DESKTOP=1` 把会话判成 `desktop` 并下发我们无法应答的 `desktop_ui` 工具集。
     */
    suspend fun resumeSession(profile: String?, storedSessionId: String): ResumedSession {
        val params = buildJsonObject {
            put("session_id", storedSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("omit_messages", true)
            put("cols", 100)
            put("source", SessionSurface.CLIENT)
        }
        val result = try {
            gateway.call(RpcMethods.SESSION_RESUME, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_RESUME)
        }
        val obj = result as? JsonObject ?: throw AppError.Protocol("session.resume is not an object")
        val runtimeId = obj.stringOrNull("session_id")
            ?: throw AppError.Protocol("session.resume returned no session_id")
        val info = obj["info"] as? JsonObject
        return ResumedSession(
            runtimeId = runtimeId,
            cwd = info?.stringOrNull("cwd"),
            model = info?.stringOrNull("model"),
            provider = info?.stringOrNull("provider"),
            reasoningEffort = info?.stringOrNull("reasoning_effort"),
            executionGeneration = (obj["execution_generation"] as? JsonPrimitive)?.intOrNull,
            revision = (obj["revision"] as? JsonPrimitive)?.intOrNull,
        )
    }

    /**
     * 发送一条用户消息；ack 只代表"已被接受"，正文靠事件推进。
     *
     * canonical 契约（`gateway/session_controls.py::submission`）有两条硬要求：
     * 1. **必须**带 `submission_id`（或 `input_id`）—— 它是这次 admission 的幂等身份，
     *    缺了后端直接 `4001 invalid_params`（这就是"一发消息就报 4001"的原因）；
     * 2. 键集合是闭集，**不能带 `profile`** —— 会话已经由 live runtime id 定位。
     */
    suspend fun submitPrompt(
        runtimeSessionId: String,
        text: String,
    ): PromptAck {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
            put("submission_id", newRequestId())
        }
        val result = try {
            // 硬约束 3：ack 超时按 1800s，不能退化成 30s。
            gateway.call(RpcMethods.PROMPT_SUBMIT, params, Timeouts.PROMPT_SUBMIT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.PROMPT_SUBMIT)
        }
        val obj = result as? JsonObject ?: JsonObject(emptyMap())
        return PromptAck(
            status = obj.stringOrNull("status"),
            userRowId = (obj["user_row_id"] as? JsonPrimitive)?.longOrNull,
        )
    }

    /**
     * 生成中纠偏（`session.steer`）：把这条用户消息注入当前轮，不打断它。
     *
     * 后端返回 `status=queued` 表示已接受。agent 不支持 steer 时会报错，
     * 调用方据此退回"排队等这一轮结束"。
     *
     * `executionGeneration` 在 canonical 契约里是**必填**（`CanonicalCorrectionParams`），
     * 拿不到就只能报错 —— 让调用方先 resume 一次，而不是发一个注定 4001 的请求。
     */
    suspend fun steer(
        profile: String?,
        runtimeSessionId: String,
        text: String,
        executionGeneration: Int?,
    ): String? {
        if (executionGeneration == null) throw AppError.Protocol("session.steer 需要 execution_generation")
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("execution_generation", executionGeneration)
            put("text", text)
        }
        val result = try {
            gateway.call(RpcMethods.SESSION_STEER, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_STEER)
        }
        return (result as? JsonObject)?.stringOrNull("status")
    }

    /**
     * 从**某条消息**分支（保留到它为止）。
     *
     * canonical gateway 没有 `session.branch`（-32601），统一走
     * `session.mutate {operation:"branch", payload:{through_message_id|title}}`。
     * `throughMessageId` 是**物理行 id**（`history_row_id`），不是"前 N 条"。
     */
    suspend fun branch(
        runtimeSessionId: String,
        throughMessageId: Long?,
        name: String? = null,
        revision: Int?,
        executionGeneration: Int?,
    ): BranchFrom {
        val payload = buildJsonObject {
            name?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            throughMessageId?.let { put("through_message_id", it) }
        }
        val result = mutateSession(
            sessionId = runtimeSessionId,
            operation = "branch",
            payload = payload,
            revision = revision,
            executionGeneration = executionGeneration,
            timeoutMs = Timeouts.PROMPT_SUBMIT_MS,
        )
        return BranchFrom.ofMutation(result, "session.mutate/branch")
    }

    /** 整段克隆（不带 `through_message_id` 就是整份克隆）。 */
    suspend fun branchWhole(
        runtimeSessionId: String,
        name: String? = null,
        revision: Int?,
        executionGeneration: Int?,
    ): BranchFrom {
        val result = mutateSession(
            sessionId = runtimeSessionId,
            operation = "branch",
            payload = buildJsonObject { name?.takeIf { it.isNotBlank() }?.let { put("title", it) } },
            revision = revision,
            executionGeneration = executionGeneration,
            timeoutMs = Timeouts.PROMPT_SUBMIT_MS,
        )
        return BranchFrom.ofMutation(result, "session.mutate/branch")
    }

    /**
     * 回退到某条消息之前（`session.mutate {operation:"rewind"}`）。
     *
     * canonical 契约里 `prompt.submit` **不接受** `truncate_before_message_id` /
     * `confirm_truncate`（那是旧 `METHODS` 契约的键，闭集校验直接 4001），
     * 回退必须先 mutate rewind，再单独 submit 一条新消息。
     */
    suspend fun rewindTo(
        runtimeSessionId: String,
        targetMessageRowId: Long,
        revision: Int?,
        executionGeneration: Int?,
    ) {
        mutateSession(
            sessionId = runtimeSessionId,
            operation = "rewind",
            payload = buildJsonObject { put("target_message_id", targetMessageRowId) },
            revision = revision,
            executionGeneration = executionGeneration,
            requiresGeneration = true,
            timeoutMs = Timeouts.PROMPT_SUBMIT_MS,
        )
    }

    /**
     * 上下文占用明细 —— 也是**用量条唯一的数据源**。
     *
     * 0.23.0 的 canonical gateway 不再提供 `session.usage` 这个查询方法（官方 Desktop 那边
     * usage 改成按 `session.usage` **事件**推送 + `session.context_breakdown` 查询），
     * 所以这里同一份数据既喂顶栏的用量条，也喂点开后的明细。
     */
    suspend fun loadContextBreakdown(profile: String?, runtimeSessionId: String): ContextBreakdownDto {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val result = try {
            gateway.call(RpcMethods.SESSION_CONTEXT_BREAKDOWN, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_CONTEXT_BREAKDOWN)
        }
        return RpcJson.json.decodeFromJsonElement(ContextBreakdownDto.serializer(), result)
    }

    /** 停止当前生成；真正的收尾以 `message.complete(status=interrupted)` 事件为准。 */
    suspend fun interrupt(profile: String?, runtimeSessionId: String, executionGeneration: Int?) {
        if (executionGeneration == null) throw AppError.Protocol("session.interrupt 需要 execution_generation")
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("execution_generation", executionGeneration)
        }
        try {
            gateway.call(RpcMethods.SESSION_INTERRUPT, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_INTERRUPT)
        }
    }

    /**
     * `session.mutate`：canonical gateway 上所有"改会话"的统一入口
     * （rename / archive / delete / branch / rewind / workspace / reasoning / model）。
     *
     * ⚠️ 参数集是**精确匹配**（`gateway/session_mutations.py::_FIELDS`）：
     * 只能是 `session_id` + `request_id` + `expected_revision` + `operation` + `payload`
     * （外加可选的 `expected_generation`）。**多传一个 `profile` 就 4001** ——
     * it 不在 canonical 的 profile 隐式剥离名单里，会直接破坏精确匹配。
     *
     * `revision_conflict` / `stale_generation` 的拒绝里带着后端当前的新鲜 fence
     * （`data.revision` / `data.execution_generation`），所以这里用**同一个 request_id**
     * 再试一次 —— 幂等，且不会把上一个 mutation 做成两次。
     */
    suspend fun mutateSession(
        sessionId: String,
        operation: String,
        payload: JsonObject,
        revision: Int?,
        executionGeneration: Int? = null,
        requiresGeneration: Boolean = false,
        timeoutMs: Long = Timeouts.WRITE_MS,
    ): JsonElement {
        if (revision == null) throw AppError.Protocol("session.mutate 需要 expected_revision")
        if (requiresGeneration && executionGeneration == null) {
            throw AppError.Protocol("session.mutate/$operation 需要 expected_generation")
        }
        val requestId = newRequestId()
        var fenceRevision = revision
        var fenceGeneration = executionGeneration
        var attempt = 0
        while (true) {
            val params = buildJsonObject {
                put("session_id", sessionId)
                put("request_id", requestId)
                put("expected_revision", fenceRevision)
                fenceGeneration?.let { put("expected_generation", it) }
                put("operation", operation)
                put("payload", payload)
            }
            try {
                return gateway.call(RpcMethods.SESSION_MUTATE, params, timeoutMs)
            } catch (conflict: AppError.Server) {
                val data = conflict.data as? JsonObject
                val freshRevision = (data?.get("revision") as? JsonPrimitive)?.intOrNull
                if (attempt++ >= 1 || freshRevision == null) {
                    throw ErrorMapper.from(conflict, RpcMethods.SESSION_MUTATE)
                }
                fenceRevision = freshRevision
                (data?.get("execution_generation") as? JsonPrimitive)?.intOrNull?.let { fenceGeneration = it }
            } catch (throwable: Throwable) {
                throw ErrorMapper.from(throwable, RpcMethods.SESSION_MUTATE)
            }
        }
    }

    // ── 审批 ────────────────────────────────────────────────────────────

    /**
     * 该会话现在是否还活着（`session.active_list`）。
     *
     * 切回一个此前切走的会话时用它判断"那轮是否已经结束"：不在列表里说明不会再有任何事件，
     * 只能重读 transcript。查询失败时按"还活着"处理 —— 宁可少补拉，也不要误清掉流式内容。
     */
    suspend fun isSessionLive(profile: String?, sessionId: String): Boolean {
        val params = buildJsonObject {
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val result = runCatching {
            gateway.call(RpcMethods.SESSION_ACTIVE_LIST, params, Timeouts.DEFAULT_MS)
        }.getOrNull() ?: return true
        val rows = (result as? JsonObject)?.get("sessions") as? JsonArray ?: return true
        return rows.any { row ->
            val item = row as? JsonObject ?: return@any false
            val key = (item["session_key"] as? JsonPrimitive)?.contentOrNull
            val id = (item["id"] as? JsonPrimitive)?.contentOrNull
            key == sessionId || id == sessionId
        }
    }

    /**
     * 本会话还没解决的审批（重连 / 断线期间发出的请求）。
     *
     * 请求发出时本端不在线，响应帧就永远收不到了 —— 只能靠这里补拉，
     * 再用 `approval.respond` 交付决策。
     */
    suspend fun pendingApprovals(profile: String?, runtimeSessionId: String): List<PendingApproval> {
        val result = try {
            gateway.call(
                RpcMethods.APPROVAL_PENDING,
                sessionParams(profile, runtimeSessionId),
                Timeouts.DEFAULT_MS,
            )
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.APPROVAL_PENDING)
        }
        val approvals = (result as? JsonObject)?.get("approvals") as? JsonArray ?: return emptyList()
        return approvals.mapNotNull { (it as? JsonObject)?.toPendingApproval(fromRequest = false) }
    }

    /** 告知后端审批卡片已上屏（它的超时时钟从这里开始算）；返回 false 表示这条已不在队列里。 */
    suspend fun acknowledgeApproval(
        profile: String?,
        runtimeSessionId: String,
        requestId: String,
    ): Boolean {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("request_id", requestId)
        }
        val result = gateway.call(RpcMethods.APPROVAL_RECEIVED, params, Timeouts.DEFAULT_MS)
        return (result as? JsonObject)
            ?.get("acknowledged")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false
    }

    /**
     * 交付决策（补拉回来的卡片只能走这条路）；返回本次决策解开了几条等待。
     *
     * canonical 契约（`CanonicalApprovalParams`）要的是 `prompt_id` + `choice` +
     * **`execution_generation`**；旧写法传 `request_id` 会被闭集校验判 `4001 invalid_params`。
     */
    suspend fun respondApproval(
        profile: String?,
        runtimeSessionId: String,
        promptId: String?,
        choice: String,
        executionGeneration: Int?,
    ): Int {
        if (executionGeneration == null) throw AppError.Protocol("approval.respond 需要 execution_generation")
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("execution_generation", executionGeneration)
            promptId?.takeIf { it.isNotBlank() }?.let { put("prompt_id", it) }
            put("choice", choice)
        }
        val result = try {
            gateway.call(RpcMethods.APPROVAL_RESPOND, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.APPROVAL_RESPOND)
        }
        return (result as? JsonObject)
            ?.get("resolved")
            ?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: 0
    }

    private fun sessionParams(profile: String?, runtimeSessionId: String): JsonObject = buildJsonObject {
        put("session_id", runtimeSessionId)
        profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
    }

    // ── 后台任务 ────────────────────────────────────────────────────────

    /**
     * 本会话拥有的后台进程快照（`process.list`）。
     *
     * 行里的 `session_id` 是**进程 id**（registry 的字段命名），`output_tail` 是最近 4000 字符。
     */
    suspend fun loadProcesses(profile: String?, runtimeSessionId: String): List<BackgroundTask> {
        val result = try {
            gateway.call(
                RpcMethods.PROCESS_LIST,
                sessionParams(profile, runtimeSessionId),
                Timeouts.DEFAULT_MS,
            )
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.PROCESS_LIST)
        }
        val rows = (result as? JsonObject)?.get("processes") as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("session_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackgroundTask(
                id = id,
                command = obj.stringOrNull("command").orEmpty(),
                status = obj.stringOrNull("status").orEmpty().ifBlank { "running" },
                exitCode = (obj["exit_code"] as? JsonPrimitive)?.intOrNull,
                uptimeSeconds = (obj["uptime_seconds"] as? JsonPrimitive)?.longOrNull,
                output = obj.stringOrNull("output_tail").orEmpty(),
                cwd = obj.stringOrNull("cwd"),
            )
        }
    }

    /**
     * 终止一个后台进程（`process.kill`）。
     *
     * 后端按 `session_key` 校验归属，越权会返回 `4044 no such process` —— 我们把它当"已经没了"处理。
     */
    suspend fun killProcess(profile: String?, runtimeSessionId: String, processId: String): String {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("process_id", processId)
        }
        val result = try {
            gateway.call(RpcMethods.PROCESS_KILL, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.PROCESS_KILL)
        }
        return (result as? JsonObject)?.stringOrNull("status").orEmpty()
    }

    companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 500

        /** 导出/复制一次最多拉多少条：巨型会话宁可少拿，也不要把内存打满。 */
        const val MAX_ALL_MESSAGES = 20_000

        private const val LIMIT = MAX_LIMIT

        /** `session.mutate` 的幂等 id（后端按它去重 receipt）；本地唯一即可。 */
        private fun newRequestId(): String =
            "hdc-m-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(100_000, 999_999)}"
    }
}

data class ResumedSession(
    val runtimeId: String,
    val cwd: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val reasoningEffort: String? = null,
    /**
     * `execution_generation`：0.23.0 的 canonical gateway 要求 `session.interrupt` /
     * `session.steer` / `approval.respond` **必须**带上它（缺了就是 `4001 invalid_params`），
     * 每次 `prompt.submit` 之后这个数会变，所以它只作为"当前这一代的凭据"快照。
     */
    val executionGeneration: Int? = null,
    /** 会话的 mutation revision，做 rename / archive 等编辑时的 fence。 */
    val revision: Int? = null,
)

data class PromptAck(val status: String?, val userRowId: Long?)

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

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
)

data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

private fun TranscriptRowDto.toRow(): ChatRow = ChatRow(
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
)

private const val HIDDEN_DISPLAY_KIND = "hidden"

