package dev.shinglem.hermes.feature.subagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.contract.SubagentDto
import dev.shinglem.hermes.core.contract.SubagentListDto
import dev.shinglem.hermes.core.contract.SubagentTailDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.GatewayEvent
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 子代理面板的一条（roster + 本地累积的 tail）。 */
data class SubagentEntry(
    val id: String,
    val parentId: String? = null,
    val depth: Int = 0,
    val goal: String = "",
    val status: String? = null,
    val toolCount: Int = 0,
    val lastTool: String? = null,
    val model: String? = null,
    val acceptingSteer: Boolean = false,
    /** 最近输出（只保留尾部 [SubagentViewModel.TAIL_LIMIT] 字符）。 */
    val tail: String = "",
)

data class SubagentState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: AppError? = null,
    val entries: List<SubagentEntry> = emptyList(),
    val steeringId: String? = null,
    /** 面板不知道当前会话的 runtime id（还没 resume / 已切走）。 */
    val unavailable: Boolean = false,
    val notice: String? = null,
) {
    val running: Int get() = entries.count { it.status == SubagentViewModel.STATUS_RUNNING }

    val isEmpty: Boolean get() = entries.isEmpty()
}

/** 会话级子代理：`subagent.list` 首屏 + `subagent.*` 事件流实时更新。 */
class SubagentViewModel(
    private val gateway: GatewayClient,
    /** 当前会话的 runtime id（没活会话时返回 null）。 */
    private val runtime: () -> RuntimeContext?,
) : ViewModel() {
    data class RuntimeContext(val profile: String?, val runtimeId: String)

    private val _state = MutableStateFlow(SubagentState())
    val state: StateFlow<SubagentState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            gateway.events
                .filter { it.type in GatewayEventTypes.SUBAGENT_EVENTS }
                .collect(::handleEvent)
        }
    }

    fun open() {
        _state.update { it.copy(open = true) }
        refresh()
    }

    fun close() = _state.update { it.copy(open = false) }

    /** 拉一次 roster（首屏 / 手动刷新）。 */
    fun refresh() {
        val ctx = runtime()
        if (ctx == null) {
            _state.update { it.copy(unavailable = true, entries = emptyList(), loading = false) }
            return
        }
        _state.update { it.copy(loading = true, unavailable = false, error = null) }
        viewModelScope.launch {
            try {
                val params = buildJsonObject {
                    put("session_id", ctx.runtimeId)
                    ctx.profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
                }
                val result = gateway.call(RpcMethods.SUBAGENT_LIST, params, Timeouts.DEFAULT_MS)
                val dto = RpcJson.json.decodeFromJsonElement(SubagentListDto.serializer(), result)
                _state.update { current ->
                    current.copy(
                        loading = false,
                        entries = dto.subagents.map { it.toEntry(tail = current.tailOf(it.subagentId)) },
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(loading = false, error = ErrorMapper.from(throwable, RpcMethods.SUBAGENT_LIST))
                }
            }
        }
    }

    /** 取某个子代理的最近输出（`subagent.tail`，最多 16KB）。 */
    fun loadTail(subagentId: String) {
        val ctx = runtime() ?: return
        viewModelScope.launch {
            try {
                val params = buildJsonObject {
                    put("session_id", ctx.runtimeId)
                    put("subagent_id", subagentId)
                }
                val result = gateway.call(RpcMethods.SUBAGENT_TAIL, params, Timeouts.DEFAULT_MS)
                val dto = RpcJson.json.decodeFromJsonElement(SubagentTailDto.serializer(), result)
                if (!dto.available) {
                    _state.update { it.copy(notice = "这个子代理没有可读取的输出") }
                    return@launch
                }
                _state.update { current ->
                    current.copy(
                        entries = current.entries.map {
                            if (it.id == subagentId) it.copy(tail = dto.text.takeLast(TAIL_LIMIT)) else it
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.debug("subagent.tail failed: ${throwable.message}")
                _state.update { it.copy(notice = "读取子代理输出失败") }
            }
        }
    }

    fun steer(subagentId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val ctx = runtime() ?: return
        if (_state.value.steeringId != null) return
        _state.update { it.copy(steeringId = subagentId, notice = null) }
        viewModelScope.launch {
            try {
                val params = buildJsonObject {
                    put("session_id", ctx.runtimeId)
                    put("subagent_id", subagentId)
                    put("text", trimmed)
                }
                val result = gateway.call(RpcMethods.SUBAGENT_STEER, params, Timeouts.DEFAULT_MS)
                val status = (result as? JsonObject)?.stringOrNull("status")
                _state.update {
                    it.copy(
                        steeringId = null,
                        notice = if (status == "queued") "已发送给子代理" else "子代理没有接受这条指令",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        steeringId = null,
                        error = ErrorMapper.from(throwable, RpcMethods.SUBAGENT_STEER),
                    )
                }
            }
        }
    }

    fun interrupt(subagentId: String) {
        val ctx = runtime() ?: return
        viewModelScope.launch {
            try {
                val params = buildJsonObject {
                    put("session_id", ctx.runtimeId)
                    put("subagent_id", subagentId)
                }
                gateway.call(RpcMethods.SUBAGENT_INTERRUPT, params, Timeouts.DEFAULT_MS)
                _state.update { current ->
                    current.copy(
                        entries = current.entries.map {
                            if (it.id == subagentId) it.copy(status = STATUS_INTERRUPTED) else it
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(error = ErrorMapper.from(throwable, RpcMethods.SUBAGENT_INTERRUPT))
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun handleEvent(event: GatewayEvent) {
        val ctx = runtime() ?: return
        if (event.sessionId != null && event.sessionId != ctx.runtimeId) return
        val payload = event.payload as? JsonObject ?: return
        val id = payload.stringOrNull("subagent_id")?.takeIf { it.isNotBlank() } ?: return

        _state.update { current ->
            val existing = current.entries.firstOrNull { it.id == id }
            val base = existing ?: SubagentEntry(
                id = id,
                parentId = payload.stringOrNull("parent_id"),
                depth = (payload["depth"] as? JsonPrimitive)?.intOrNull ?: 0,
                goal = payload.stringOrNull("goal").orEmpty(),
                model = payload.stringOrNull("model"),
            )
            val toolCount = (payload["tool_count"] as? JsonPrimitive)?.intOrNull ?: base.toolCount
            val lastTool = payload.stringOrNull("tool_name") ?: payload.stringOrNull("tool_preview")
                ?: base.lastTool
            val appended = when (event.type) {
                GatewayEventTypes.SUBAGENT_THINKING,
                GatewayEventTypes.SUBAGENT_START,
                -> payload.stringOrNull("text").orEmpty()

                GatewayEventTypes.SUBAGENT_TOOL -> payload.stringOrNull("tool_preview")
                    ?.let { "[$it]\n" }.orEmpty()

                else -> ""
            }
            val updated = base.copy(
                status = statusOf(event.type) ?: base.status,
                toolCount = toolCount,
                lastTool = lastTool,
                acceptingSteer = payload.stringOrNull("accepting_steer") != "false",
                tail = (base.tail + appended).takeLast(TAIL_LIMIT),
            )
            val others = current.entries.filterNot { it.id == id }
            current.copy(entries = (others + updated).sortedBy { it.depth })
        }
    }

    private fun statusOf(type: String): String? = when (type) {
        GatewayEventTypes.SUBAGENT_SPAWN_REQUESTED, GatewayEventTypes.SUBAGENT_START -> STATUS_RUNNING
        GatewayEventTypes.SUBAGENT_COMPLETE -> STATUS_DONE
        else -> null
    }

    private fun SubagentDto.toEntry(tail: String) = SubagentEntry(
        id = subagentId,
        parentId = parentId,
        depth = depth ?: 0,
        goal = goal,
        status = status,
        toolCount = toolCount ?: 0,
        lastTool = lastTool,
        model = model,
        acceptingSteer = acceptingSteer ?: true,
        tail = tail,
    )

    private fun SubagentState.tailOf(id: String): String = entries.firstOrNull { it.id == id }?.tail.orEmpty()

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_INTERRUPTED = "interrupted"
        const val TAIL_LIMIT = 8_000
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
