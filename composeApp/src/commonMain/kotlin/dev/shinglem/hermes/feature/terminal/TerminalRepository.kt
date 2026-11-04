package dev.shinglem.hermes.feature.terminal

import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `terminal.read` 的应答体（字段与官方 `TerminalReadResult` 完全一致，见 ADR-041）。
 *
 * 行号是**绝对行号**（0 = 最老的滚动历史行），`text` 是这些行的纯文本（不含 SGR）。
 */
@Serializable
data class TerminalReadPayload(
    @SerialName("total_lines") val totalLines: Int,
    val start: Int,
    val end: Int,
    @SerialName("viewport_rows") val viewportRows: Int,
    @SerialName("cursor_row") val cursorRow: Int,
    val text: String,
)

/**
 * 终端与 gateway 的接缝：
 *
 * - **server→client `terminal.read`**：agent 的 `read_terminal` 工具要读"用户正在看的终端"，
 *   必须由窗口应答（单窗口单实例，所以我们直接答）。幂等由 [GatewayClient] 按请求 id 缓存。
 * - **事件 `agent.terminal.output` / `terminal.close`**：agent 后台进程输出以**只读镜像标签**出现，
 *   关闭标签只关视图、不杀进程。
 */
class TerminalRepository(private val gateway: GatewayClient) {

    private val json = Json { ignoreUnknownKeys = true }

    fun register(viewModel: TerminalViewModel, scope: CoroutineScope) {
        gateway.onServerRequest(READ_TERMINAL) { _, params ->
            val start = params.intField("start")
            val count = params.intField("count")
            val payload = viewModel.readBuffer?.invoke(start, count)
                ?: EMPTY_PAYLOAD
            JsonPrimitive(json.encodeToString(TerminalReadPayload.serializer(), payload))
        }

        scope.launch {
            gateway.events.collect { event ->
                when (event.type) {
                    EVENT_TERMINAL_OUTPUT -> {
                        val obj = event.payload as? JsonObject ?: return@collect
                        val processId = obj.stringField("process_id") ?: return@collect
                        val chunk = obj.stringField("chunk").orEmpty()
                        viewModel.appendMirror(processId, chunk)
                    }

                    EVENT_TERMINAL_CLOSE -> {
                        val obj = event.payload as? JsonObject ?: return@collect
                        obj.stringField("process_id")?.let(viewModel::closeMirror)
                    }
                }
            }
        }
    }

    fun unregister() {
        gateway.removeServerRequestHandler(READ_TERMINAL)
    }

    /**
     * `terminal.resize`：把终端可见列数报给后端（它只记在会话上，用于服务端渲染宽度）。
     *
     * best-effort —— 断线、超时都不该影响用户正在用的终端，所以失败只记日志。
     */
    suspend fun reportCols(sessionId: String, cols: Int) {
        if (sessionId.isBlank() || cols <= 0) return
        runCatching {
            gateway.call(
                RESIZE_TERMINAL,
                buildJsonObject {
                    put("session_id", sessionId)
                    put("cols", cols)
                },
                Timeouts.HEALTH_MS,
            )
        }.onFailure { Log.warn("terminal.resize 失败：${it.message}") }
    }

    companion object {
        const val READ_TERMINAL = "terminal.read"
        const val RESIZE_TERMINAL = "terminal.resize"
        const val EVENT_TERMINAL_OUTPUT = "agent.terminal.output"
        const val EVENT_TERMINAL_CLOSE = "terminal.close"

        /** 没有终端时的应答：空文本 + 0 行（官方同样返回空，工具侧据此提示"没有终端"）。 */
        val EMPTY_PAYLOAD = TerminalReadPayload(
            totalLines = 0,
            start = 0,
            end = 0,
            viewportRows = 0,
            cursorRow = 0,
            text = "",
        )
    }
}

private fun kotlinx.serialization.json.JsonElement?.intField(name: String): Int? {
    val obj = this as? JsonObject ?: return null
    return runCatching { obj[name]?.jsonPrimitive?.intOrNull }.getOrNull()
}

private fun JsonObject.stringField(name: String): String? =
    runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
