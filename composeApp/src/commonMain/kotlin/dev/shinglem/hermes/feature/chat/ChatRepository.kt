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
import dev.shinglem.hermes.core.transport.SessionFence
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.mutateSession
import dev.shinglem.hermes.core.transport.sessionControl
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
        val prompts = (obj["prompts"] as? JsonArray)?.mapNotNull { row ->
            (row as? JsonObject)?.toResumedPrompt()
        }.orEmpty()
        val pending = (obj["pending"] as? JsonArray)?.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val admissionId = item.stringOrNull("admission_id") ?: return@mapNotNull null
            ResumedPending(
                admissionId = admissionId,
                text = item.stringOrNull("text").orEmpty(),
                status = item.stringOrNull("status").orEmpty(),
                sequence = (item["sequence"] as? JsonPrimitive)?.intOrNull,
            )
        }.orEmpty()
        return ResumedSession(
            runtimeId = runtimeId,
            cwd = info?.stringOrNull("cwd"),
            model = info?.stringOrNull("model"),
            provider = info?.stringOrNull("provider"),
            reasoningEffort = info?.stringOrNull("reasoning_effort"),
            executionGeneration = (obj["execution_generation"] as? JsonPrimitive)?.intOrNull,
            revision = (obj["revision"] as? JsonPrimitive)?.intOrNull,
            running = (obj["running"] as? JsonPrimitive)?.booleanOrNull == true,
            prompts = prompts,
            pending = pending,
        )
    }

    /**
     * 发送一条用户消息；ack 只代表"已被接受"，正文靠事件推进。
     *
     * canonical 契约（`gateway/session_controls.py::submission`）要求：
     * 1. **必须**带 `submission_id`（或 `input_id`）—— 这次 admission 的幂等身份，
     *    缺了后端直接 `4001 invalid_params`；
     * 2. **必须**带 `profile`：daemon 是多 profile 复用的（连接 actor 属于启动 home），
     *    跨 profile 的会话要靠它路由到对应 authority，否则订阅匹配不上 →
     *    `4001 permission_denied`。`gateway/session_controls.py::_PROFILE_IMPLICIT` 里
     *    含 `prompt.submit`，所以路由到自家 home 时这个键会被安全剥离。
     */
    suspend fun submitPrompt(
        profile: String?,
        runtimeSessionId: String,
        text: String,
    ): PromptAck {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
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
            admissionId = obj.stringOrNull("admission_id"),
            sequence = (obj["sequence"] as? JsonPrimitive)?.intOrNull,
            executionGeneration = (obj["execution_generation"] as? JsonPrimitive)?.intOrNull,
        )
    }

    /**
     * 把一条**还在排队**的消息立刻交给正在跑的那一轮（`prompt.steer_queued`）。
     *
     * 这就是"插队"：admission 仍在 `queued` 时，它的文本会被送进当前轮，
     * 该 admission 读作 `started`（`steering` 标记），不会自己再单独跑一轮；
     * 当前轮把它消费掉就按 `steered` 收尾，没消费则退回原队列位置。
     *
     * 这条请求**不要** generation：它按 `admission_id` 定位，不是按执行代。
     */
    suspend fun steerQueued(profile: String?, runtimeSessionId: String, admissionId: String): String? {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("admission_id", admissionId)
        }
        val result = try {
            gateway.call(RpcMethods.PROMPT_STEER_QUEUED, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.PROMPT_STEER_QUEUED)
        }
        return (result as? JsonObject)?.stringOrNull("status")
    }

    /** 取消一条还在排队的消息（`prompt.cancel`）。 */
    suspend fun cancelQueued(profile: String?, runtimeSessionId: String, admissionId: String): String? {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("admission_id", admissionId)
        }
        val result = try {
            gateway.call(RpcMethods.PROMPT_CANCEL, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.PROMPT_CANCEL)
        }
        return (result as? JsonObject)?.stringOrNull("status")
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
        val receipt = gateway.sessionControl(
            method = RpcMethods.SESSION_STEER,
            sessionId = runtimeSessionId,
            profile = profile,
            fence = SessionFence(generation = executionGeneration),
            extra = buildJsonObject { put("text", text) },
        )
        return (receipt.result as? JsonObject)?.stringOrNull("status")
    }

    /**
     * 从**某条消息**分支（保留到它为止）。
     *
     * canonical gateway 没有 `session.branch`（-32601），统一走
     * `session.mutate {operation:"branch", payload:{through_message_id|title}}`。
     * `throughMessageId` 是**物理行 id**（`history_row_id`），不是"前 N 条"。
     */
    suspend fun branch(
        profile: String?,
        runtimeSessionId: String,
        throughMessageId: Long?,
        name: String? = null,
        fence: SessionFence,
    ): BranchFrom {
        val payload = buildJsonObject {
            name?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            throughMessageId?.let { put("through_message_id", it) }
        }
        val receipt = gateway.mutateSession(
            sessionId = runtimeSessionId,
            profile = profile,
            operation = "branch",
            payload = payload,
            fence = fence,
            timeoutMs = Timeouts.PROMPT_SUBMIT_MS,
        )
        return BranchFrom.ofMutation(receipt.result, "session.mutate/branch")
    }

    /** 整段克隆（不带 `through_message_id` 就是整份克隆）。 */
    suspend fun branchWhole(
        profile: String?,
        runtimeSessionId: String,
        name: String? = null,
        fence: SessionFence,
    ): BranchFrom {
        val receipt = gateway.mutateSession(
            sessionId = runtimeSessionId,
            profile = profile,
            operation = "branch",
            payload = buildJsonObject { name?.takeIf { it.isNotBlank() }?.let { put("title", it) } },
            fence = fence,
            timeoutMs = Timeouts.PROMPT_SUBMIT_MS,
        )
        return BranchFrom.ofMutation(receipt.result, "session.mutate/branch")
    }

    /**
     * 回退到某条消息之前（`session.mutate {operation:"rewind"}`）。
     *
     * canonical 契约里 `prompt.submit` **不接受** `truncate_before_message_id` /
     * `confirm_truncate`（那是旧 `METHODS` 契约的键，闭集校验直接 4001），
     * 回退必须先 mutate rewind，再单独 submit 一条新消息。
     */
    suspend fun rewindTo(
        profile: String?,
        runtimeSessionId: String,
        targetMessageRowId: Long,
        fence: SessionFence,
    ): SessionFence {
        val receipt = gateway.mutateSession(
            sessionId = runtimeSessionId,
            profile = profile,
            operation = "rewind",
            payload = buildJsonObject { put("target_message_id", targetMessageRowId) },
            fence = fence,
            requiresGeneration = true,
            timeoutMs = Timeouts.PROMPT_SUBMIT_MS,
        )
        return receipt.fence
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
        gateway.sessionControl(
            method = RpcMethods.SESSION_INTERRUPT,
            sessionId = runtimeSessionId,
            profile = profile,
            fence = SessionFence(generation = executionGeneration),
        )
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

    /** 补拉路径的 clarify 作答（`clarify.respond`）：要 `prompt_id` + `execution_generation`。 */
    suspend fun respondClarify(
        profile: String?,
        runtimeSessionId: String,
        promptId: String,
        answer: String,
        executionGeneration: Int?,
    ) {
        gateway.sessionControl(
            method = RpcMethods.CLARIFY_RESPOND,
            sessionId = runtimeSessionId,
            profile = profile,
            fence = SessionFence(generation = executionGeneration),
            extra = buildJsonObject {
                put("prompt_id", promptId)
                put("answer", answer)
            },
        )
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
        val receipt = gateway.sessionControl(
            method = RpcMethods.APPROVAL_RESPOND,
            sessionId = runtimeSessionId,
            profile = profile,
            fence = SessionFence(generation = executionGeneration),
            extra = buildJsonObject {
                promptId?.takeIf { it.isNotBlank() }?.let { put("prompt_id", it) }
                put("choice", choice)
            },
        )
        return (receipt.result as? JsonObject)
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
     * `session.steer` / `approval.respond` / `clarify.respond` **必须**带上它
     * （缺了就是 `4001 invalid_params`），每次 `prompt.submit` 之后这个数会变，
     * 所以它只作为"当前这一代的凭据"快照。
     */
    val executionGeneration: Int? = null,
    /** 会话的 mutation revision，做 rename / archive 等编辑时的 fence。 */
    val revision: Int? = null,
    /** 会话此刻是否还在跑（`running`）：卡在 clarify 时会一直是 true。 */
    val running: Boolean = false,
    /**
     * 快照里**还没被应答**的请求（`prompts`）：`kind=clarify` 的那条只能靠
     * `clarify.respond` 解开，否则 turn 会一直停在 `waiting`。
     */
    val prompts: List<ResumedPrompt> = emptyList(),
    /** 快照里**还在排队**的消息（`pending[]`）：重连后队列不能丢。 */
    val pending: List<ResumedPending> = emptyList(),
)

/** `session.resume` 快照里的 `pending[]`（一条已提交、还没跑完的 admission）。 */
data class ResumedPending(
    val admissionId: String,
    val text: String,
    val status: String,
    val sequence: Int? = null,
) {
    val isQueued: Boolean get() = status == "queued"
}

/** `session.resume` 快照里的 `prompts[]`（`clarify` / `approval` 两条补拉路径都靠它）。 */
data class ResumedPrompt(
    val kind: String,
    val promptId: String,
    val executionGeneration: Int? = null,
    val question: String = "",
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    /** `kind=approval` 时的命令与说明。 */
    val command: String = "",
    val description: String = "",
)

data class PromptAck(
    /** `queued` / `started` / `unknown` / `terminal`（`AdmissionStatus.status`）。 */
    val status: String?,
    val userRowId: Long?,
    /** 这次提交的 admission 身份：排队时要靠它取消 / 插队。 */
    val admissionId: String? = null,
    val sequence: Int? = null,
    val executionGeneration: Int? = null,
) {
    /** 还在排队（后端接了但这一轮还没轮到它）。 */
    val isQueued: Boolean get() = status == "queued" || status == "unknown"
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * 一条 `kind=approval|clarify` 的待应答请求。
 *
 * **快照的 `prompts[]` 与事件 `approval.request` / `clarify.request` 的 payload 是同构的**，
 * 所以两条来源共用这一个解析器（见 `.design/详细设计/工程与代码/11-后端API调用规范.md` §5）。
 */
internal fun JsonObject.toResumedPrompt(): ResumedPrompt? {
    val kind = stringOrNull("kind") ?: return null
    val promptId = stringOrNull("prompt_id") ?: return null
    return ResumedPrompt(
        kind = kind,
        promptId = promptId,
        executionGeneration = (this["execution_generation"] as? JsonPrimitive)?.intOrNull,
        question = stringOrNull("question").orEmpty(),
        choices = (this["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList(),
        multiSelect = (this["multi_select"] as? JsonPrimitive)?.booleanOrNull == true,
        command = stringOrNull("command").orEmpty(),
        description = stringOrNull("description").orEmpty(),
    )
}

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

