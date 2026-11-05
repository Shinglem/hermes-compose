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
import kotlin.time.Instant

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
        )
    }

    /** 发送一条用户消息；ack 只代表"已被接受"，正文靠事件推进。 */
    suspend fun submitPrompt(
        profile: String?,
        runtimeSessionId: String,
        text: String,
        /** 回退/重发：截断到这条消息之前（后端要求显式 `confirm_truncate`）。 */
        truncateBeforeMessageId: String? = null,
        /** 截断后历史为空时后端还要这一个确认。 */
        confirmEmptyTruncate: Boolean = false,
    ): PromptAck {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("text", text)
            if (truncateBeforeMessageId != null) {
                put("truncate_before_message_id", truncateBeforeMessageId)
                put("confirm_truncate", true)
                if (confirmEmptyTruncate) put("confirm_empty_truncate", true)
            }
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
     */
    suspend fun steer(profile: String?, runtimeSessionId: String, text: String): String? {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
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
     * 从**某条消息**分支（截到它之前，要求会话活跃）。
     *
     * `count` 是"保留前 N 条"；调用方按消息在整段历史里的序号换算。
     */
    suspend fun branch(profile: String?, runtimeSessionId: String, count: Int, name: String? = null): BranchFrom {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            put("count", count)
            name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        }
        val result = try {
            gateway.call(RpcMethods.SESSION_BRANCH, params, Timeouts.PROMPT_SUBMIT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_BRANCH)
        }
        return BranchFrom.of(result, "session.branch")
    }

    /** 整段克隆（不回传 transcript，省一次大 payload）。 */
    suspend fun branchWhole(profile: String?, runtimeSessionId: String, name: String? = null): BranchFrom {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        }
        val result = try {
            gateway.call(RpcMethods.SESSION_BRANCH_WHOLE, params, Timeouts.PROMPT_SUBMIT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_BRANCH_WHOLE)
        }
        return BranchFrom.of(result, "session.branch_whole")
    }

    /** 上下文 / token 用量（`session.usage`）。 */
    suspend fun loadUsage(profile: String?, runtimeSessionId: String): UsageDto {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val result = try {
            gateway.call(RpcMethods.SESSION_USAGE, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_USAGE)
        }
        return RpcJson.json.decodeFromJsonElement(UsageDto.serializer(), result)
    }

    /** 上下文占用明细（只在用户点开用量条时才拉）。 */
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
    suspend fun interrupt(profile: String?, runtimeSessionId: String) {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        try {
            gateway.call(RpcMethods.SESSION_INTERRUPT, params, Timeouts.DEFAULT_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.SESSION_INTERRUPT)
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

    /** 交付决策（补拉回来的卡片只能走这条路）；返回本次决策解开了几条等待。 */
    suspend fun respondApproval(
        profile: String?,
        runtimeSessionId: String,
        requestId: String?,
        choice: String,
    ): Int {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
            requestId?.takeIf { it.isNotBlank() }?.let { put("request_id", it) }
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
    }
}

data class ResumedSession(
    val runtimeId: String,
    val cwd: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val reasoningEffort: String? = null,
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

