package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.contract.RpcContracts
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.error.technicalSummary
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.LogLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

/**
 * server→client 请求的处理器：`(JSON-RPC 帧 id, params) -> 应答 result`。
 *
 * 帧 id 要传给调用方 —— `request.cancel` 撤回时带的是**这个** id（`srq-…`），
 * 不是业务 payload 里的 `request_id`。
 */
typealias ServerRequestHandler = suspend (requestId: String, params: JsonElement?) -> JsonElement?

/**
 * WebSocket + JSON-RPC 2.0 客户端（`.design/草案/03-architecture.md` §2.1 的六件事）：
 * 连接状态机、请求/响应、心跳、指数退避重连、server→client 请求路由、事件分发。
 *
 * 进程内单例：后续多窗口复用同一条连接，避免事件分叉。
 */
class GatewayClient(
    private val targetProvider: () -> GatewayTarget,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    /**
     * 连续重连失败到阈值时回调：让上层**重新解析**后端地址。
     *
     * daemon 被外部重启会换端口，而 [targetProvider] 只是读缓存的目标 ——
     * 不重新 ensure 就会一直连旧端口，症状是"连接看起来正常但一个事件都没有"（#14）。
     */
    private val onReconnectExhausted: (suspend () -> Unit)? = null,
) {
    private val http = HttpClient(CIO) {
        install(WebSockets)
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val pending = MutableStateFlow<Map<Long, CompletableDeferred<JsonElement>>>(emptyMap())
    private val handlers = MutableStateFlow<Map<String, ServerRequestHandler>>(emptyMap())

    /** server→client 请求幂等缓存：同一 id 重复到达时回放同一结果。 */
    private val answered = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    private val nextId = MutableStateFlow(0L)
    private val sendMutex = Mutex()

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var lastInboundAt: Long = 0L

    private var loop: Job? = null

    val isConnected: Boolean get() = session != null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        session = null
        _connection.value = ConnectionState.Closed
    }

    fun close() {
        stop()
        http.close()
    }

    /** 注册 server→client 请求处理器；未注册的方法统一回 `-32601`。 */
    fun onServerRequest(method: String, handler: ServerRequestHandler) {
        handlers.update { it + (method to handler) }
    }

    /** ViewModel 销毁时摘掉处理器，避免单例继续引用已死的状态持有者。 */
    fun removeServerRequestHandler(method: String) {
        handlers.update { it - method }
    }

    /**
     * 发一条请求并等待结果。
     *
     * @throws AppError.NotConnected socket 未就绪
     * @throws AppError.Timeout 超过 [timeoutMs]
     * @throws AppError.Server 服务端返回 error 对象
     */
    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = Timeouts.DEFAULT_MS,
    ): JsonElement {
        // 发送前按 canonical 契约自检：多一个键后端就是 4001，与其等一个来回，
        // 不如在本地直接指出是哪个键（backend 的 `data.fields` 是同一语义）。
        RpcContracts.problems(method, params.keys)?.let { problem ->
            logger.log(LogLevel.WARN, "rpc contract violation: $problem")
            throw AppError.Protocol(problem)
        }
        val id = nextId.updateAndGet { it + 1 }
        val deferred = CompletableDeferred<JsonElement>()
        pending.update { it + (id to deferred) }
        try {
            sendText(JsonRpc.encodeRequest(id, method, params))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw AppError.Timeout(method)
        } catch (server: AppError.Server) {
            // 后端报错时把**方法名**也写进日志：否则界面上只剩一句 "4001 invalid_params"，
            // 完全不知道是哪个调用挂了（0.23.0 的 canonical 契约是闭集，多一个键就 4001）。
            logger.log(LogLevel.WARN, "rpc failed: method=$method code=${server.code} ${server.message}")
            throw server
        } finally {
            pending.update { it - id }
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (attempt == 0) _connection.value = ConnectionState.Connecting
            try {
                openSession(targetProvider())
                attempt = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = ErrorMapper.from(throwable, "gateway.connect")
                logger.log(LogLevel.WARN, "gateway session ended: ${error.technicalSummary()}", throwable)
                _connection.value = ConnectionState.Disconnected(error)
            }
            failAllPending(AppError.NotConnected)
            if (!currentCoroutineContext().isActive) break
            attempt += 1
            // 到阈值就重新解析一次目标：连续连不上，多半是端口变了而不是网络抖。
            if (attempt % REVALIDATE_TARGET_EVERY_ATTEMPTS == 0) revalidateTarget(attempt)
            val delayMs = Timeouts.reconnectDelayMs(attempt)
            _connection.value = ConnectionState.Reconnecting(attempt, delayMs)
            delay(delayMs)
        }
        _connection.value = ConnectionState.Closed
    }

    private suspend fun revalidateTarget(attempt: Int) {
        val hook = onReconnectExhausted ?: return
        logger.log(LogLevel.INFO, "gateway reconnect: 连续 $attempt 次失败，重新解析后端地址")
        runCatching { hook() }
            .onFailure { logger.log(LogLevel.WARN, "重新解析后端地址失败：${it.message}") }
    }

    private suspend fun openSession(target: GatewayTarget) {
        http.webSocket(urlString = target.wsUrl()) {
            session = this
            lastInboundAt = Clock.System.now().toEpochMilliseconds()
            _connection.value = ConnectionState.Connected()
            logger.log(LogLevel.INFO, "gateway connected: ${target.baseUrl}")
            val heartbeat = launch { heartbeatLoop() }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    lastInboundAt = Clock.System.now().toEpochMilliseconds()
                    handleFrame(frame.readText())
                }
            } finally {
                heartbeat.cancel()
                session = null
            }
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(Timeouts.HEARTBEAT_INTERVAL_MS)
            val silentForMs = Clock.System.now().toEpochMilliseconds() - lastInboundAt
            if (silentForMs > Timeouts.HEARTBEAT_DEADLINE_MS) {
                logger.log(LogLevel.WARN, "heartbeat deadline exceeded (${silentForMs}ms); dropping socket")
                session?.close(CloseReason(CloseReason.Codes.GOING_AWAY, "heartbeat timeout"))
                return
            }
            runCatching { call(GATEWAY_PING, JsonObject(emptyMap()), Timeouts.HEALTH_MS) }
                .onFailure { logger.log(LogLevel.DEBUG, "heartbeat ping failed: ${it.message}") }
        }
    }

    private suspend fun handleFrame(text: String) {
        when (val frame = JsonRpc.parse(text)) {
            is IncomingFrame.Response -> completeResponse(frame)
            is IncomingFrame.Event -> publishEvent(frame.event)
            // 请求处理器可能要挂起等人操作（审批卡片）；放到独立协程，别把读循环堵住。
            is IncomingFrame.ServerRequest -> scope.launch {
                try {
                    answerServerRequest(frame)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    logger.log(LogLevel.WARN, "answering ${frame.method} failed", throwable)
                }
            }
            is IncomingFrame.Malformed -> logger.log(LogLevel.WARN, "malformed frame: ${frame.detail}")
        }
    }

    private fun completeResponse(frame: IncomingFrame.Response) {
        val id = (frame.id as? JsonPrimitive)?.longOrNull ?: return
        val deferred = pending.value[id] ?: return
        pending.update { it - id }
        val error = frame.error
        if (error != null) {
            deferred.completeExceptionally(AppError.Server(error.code, error.message, error.data))
        } else {
            deferred.complete(frame.result ?: JsonNull)
        }
    }

    private fun publishEvent(event: GatewayEvent) {
        if (event.type == GatewayEventTypes.READY) {
            val epoch = ((event.payload as? JsonObject)?.get("replay_epoch") as? JsonPrimitive)?.contentOrNull
            _connection.value = ConnectionState.Connected(epoch)
            // 每条新连接都要重新声明一次（后端按 transport 记录，重连后旧声明作废）。
            announceServerRequests()
        }
        _events.tryEmit(event)
    }

    /**
     * `client.capabilities {server_requests:true}`：告诉后端本端会应答 server→client 请求。
     *
     * 少了这一步后端会把本端当作"早于 server→client 请求"的旧客户端，
     * 审批请求连发都不发就直接撤回（`server_requests._unanswerable`），
     * 命令行工具拿到的是 BLOCKED 而不是弹窗。
     */
    private fun announceServerRequests() {
        scope.launch {
            try {
                val result = call(
                    CLIENT_CAPABILITIES,
                    buildJsonObject { put("server_requests", true) },
                    Timeouts.DEFAULT_MS,
                )
                val supported = (result as? JsonObject)?.get("server_requests")?.toString()
                logger.log(LogLevel.INFO, "client.capabilities announced, server requests: $supported")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logger.log(LogLevel.WARN, "client.capabilities failed: ${throwable.message}", throwable)
            }
        }
    }

    private suspend fun answerServerRequest(frame: IncomingFrame.ServerRequest) {
        // 帧 id 可能是字符串（后端用 `srq-…`）或数字：取纯文本，别把 JsonPrimitive 的引号带进 key。
        val key = frame.id.idText()
        answered.value[key]?.let { cached ->
            sendText(JsonRpc.encodeResult(frame.id, cached))
            return
        }

        val handler = handlers.value[frame.method]
        if (handler == null) {
            logger.log(LogLevel.WARN, "unhandled server request: ${frame.method}")
            sendText(
                JsonRpc.encodeError(
                    frame.id,
                    JsonRpc.METHOD_NOT_FOUND,
                    "method not supported: ${frame.method}",
                ),
            )
            return
        }

        logger.log(LogLevel.INFO, "server request ${frame.method} id=$key")

        val result = try {
            handler(frame.id.idText(), frame.params) ?: JsonNull
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            logger.log(LogLevel.ERROR, "server request handler failed: ${frame.method}", throwable)
            sendText(
                JsonRpc.encodeError(
                    frame.id,
                    JsonRpc.INTERNAL_ERROR,
                    throwable.message ?: "handler failed",
                ),
            )
            return
        }
        rememberAnswer(key, result)
        sendText(JsonRpc.encodeResult(frame.id, result))
    }

    private fun rememberAnswer(key: String, result: JsonElement) {
        answered.update { current ->
            val next = current + (key to result)
            if (next.size <= ANSWER_CACHE_LIMIT) {
                next
            } else {
                next.entries.drop(next.size - ANSWER_CACHE_LIMIT).associate { it.key to it.value }
            }
        }
    }

    private suspend fun sendText(text: String) {
        val current = session ?: throw AppError.NotConnected
        // RPC 应答与心跳可能并发写同一 socket，串行化更稳。
        sendMutex.withLock { current.send(Frame.Text(text)) }
    }

    private fun failAllPending(error: AppError) {
        val snapshot = pending.value
        pending.value = emptyMap()
        snapshot.values.forEach { it.completeExceptionally(error) }
    }

    private companion object {
        const val GATEWAY_PING = "gateway.ping"
        const val CLIENT_CAPABILITIES = "client.capabilities"
        const val ANSWER_CACHE_LIMIT = 32

        /** 连续失败这么多次就重新解析后端地址（后端换端口后要靠它跟上）。 */
        const val REVALIDATE_TARGET_EVERY_ATTEMPTS = 3
    }
}

/** 帧 id 的纯文本形式（`"srq-1"` → `srq-1`，数字原样）。 */
private fun JsonElement.idText(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()
