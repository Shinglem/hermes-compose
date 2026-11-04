package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.LogLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * REST 通道（`/api/...`）：技能、工具集、cron、配置、模型、profiles、消息平台。
 * 与会话/聊天用的 WebSocket 是两条独立通道，别把管理类调用塞进 RPC。
 */
class RestClient(
    private val targetProvider: () -> GatewayTarget,
    private val logger: AppLogger,
) {
    private val http = HttpClient(CIO) {
        expectSuccess = false
    }

    suspend fun get(path: String, timeoutMs: Long = Timeouts.DEFAULT_MS): JsonElement {
        val target = targetProvider()
        val url = target.baseUrl.trim().trimEnd('/') + path
        try {
            val (status, body) = withTimeout(timeoutMs) {
                val response = http.get(url) {
                    target.token?.takeIf { it.isNotBlank() }?.let { header(SESSION_HEADER_NAME, it) }
                }
                response.status to response.bodyAsText()
            }
            return parse(status, body, path)
        } catch (timeout: TimeoutCancellationException) {
            throw AppError.Timeout(path)
        } catch (error: AppError) {
            throw error
        } catch (throwable: Throwable) {
            logger.log(LogLevel.WARN, "REST $path failed: ${throwable.message}", throwable)
            throw ErrorMapper.from(throwable, path)
        }
    }

    /** 写请求统一入口：`method` 只接受 PUT / POST / DELETE（GET 走 [get]）。 */
    suspend fun send(
        method: RestMethod,
        path: String,
        body: JsonObject? = null,
        timeoutMs: Long = Timeouts.WRITE_MS,
    ): JsonElement {
        val target = targetProvider()
        val url = target.baseUrl.trim().trimEnd('/') + path
        try {
            val (status, text) = withTimeout(timeoutMs) {
                val response = http.request(url) {
                    this.method = when (method) {
                        RestMethod.PUT -> HttpMethod.Put
                        RestMethod.POST -> HttpMethod.Post
                        RestMethod.DELETE -> HttpMethod.Delete
                        RestMethod.PATCH -> HttpMethod.Patch
                    }
                    target.token?.takeIf { it.isNotBlank() }?.let { header(SESSION_HEADER_NAME, it) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
                response.status to response.bodyAsText()
            }
            return parse(status, text, path)
        } catch (timeout: TimeoutCancellationException) {
            throw AppError.Timeout(path)
        } catch (error: AppError) {
            throw error
        } catch (throwable: Throwable) {
            logger.log(LogLevel.WARN, "REST $method $path failed: ${throwable.message}", throwable)
            throw ErrorMapper.from(throwable, path)
        }
    }

    private fun parse(status: HttpStatusCode, body: String, path: String): JsonElement = when {
        status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.Forbidden -> throw AppError.Unauthorized

        status.value >= 400 -> throw AppError.Server(status.value, extractErrorMessage(body))

        body.isBlank() -> JsonObject(emptyMap())

        else -> RpcJson.json.parseToJsonElement(body)
    }

    /** `/api/status` 是唯一免鉴权端点，用来做健康探测与后端信息展示。 */
    suspend fun health(): BackendHealth {
        val element = get("/api/status", Timeouts.HEALTH_MS)
        val obj = element as? JsonObject ?: throw AppError.Protocol("/api/status is not an object")
        return BackendHealth(
            version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            authRequired = obj["auth_required"]?.jsonPrimitive?.booleanOrNull ?: false,
            gatewayRunning = obj["gateway_running"]?.jsonPrimitive?.booleanOrNull ?: false,
            profiles = obj["profiles"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    }

    /**
     * 按**任意** baseUrl 探一次 `/api/status`（免鉴权），返回往返毫秒。
     *
     * 与 [probeWeb] 是两个探针：Local 模式下二者同端口但语义不同 ——
     * `/api/status` 好而 `/` 是占位页，正是「payload 缺 web_dist」的形态。
     */
    suspend fun probeStatus(baseUrl: String, timeoutMs: Long = Timeouts.HEALTH_MS): Long {
        val url = baseUrl.trim().trimEnd('/') + "/api/status"
        val started = Clock.System.now().toEpochMilliseconds()
        try {
            val status = withTimeout(timeoutMs) { http.get(url).status }
            if (status.value >= 400) throw AppError.Server(status.value, "probe $url")
            return Clock.System.now().toEpochMilliseconds() - started
        } catch (timeout: TimeoutCancellationException) {
            throw AppError.Timeout("probe $url")
        } catch (error: AppError) {
            throw error
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, "probe $url")
        }
    }

    /**
     * 探 Web UI 是否可用：`GET /` 必须 2xx **且**返回 HTML。
     *
     * 只判状态码不够 —— 缺 `web_dist` 时上游在 `/` 返回的是一个占位页（也是 200），
     * 浏览器里看到的不是界面，所以还要看 content-type。
     */
    suspend fun probeWeb(baseUrl: String, timeoutMs: Long = Timeouts.HEALTH_MS): Long {
        val url = baseUrl.trim().trimEnd('/') + "/"
        val started = Clock.System.now().toEpochMilliseconds()
        try {
            val response = withTimeout(timeoutMs) { http.get(url) }
            if (response.status.value >= 400) throw AppError.Server(response.status.value, "probe $url")
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            if (!contentType.contains("text/html", ignoreCase = true)) {
                throw AppError.Server(response.status.value, "probe $url（非 HTML，Web UI 缺失）")
            }
            return Clock.System.now().toEpochMilliseconds() - started
        } catch (timeout: TimeoutCancellationException) {
            throw AppError.Timeout("probe $url")
        } catch (error: AppError) {
            throw error
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, "probe $url")
        }
    }

    fun close() = http.close()

    private fun extractErrorMessage(body: String): String =
        runCatching {
            (RpcJson.json.parseToJsonElement(body) as? JsonObject)
                ?.get("error")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull() ?: body.take(200)

    private companion object {
        const val SESSION_HEADER_NAME = "X-Hermes-Session-Token"
    }
}

data class BackendHealth(
    val version: String,
    val authRequired: Boolean,
    val gatewayRunning: Boolean,
    val profiles: List<String>,
)

/** 写请求的动词。故意用自有枚举：Ktor 的 `HttpMethod` 是类，放这儿会让调用方到处 import。 */
enum class RestMethod { PUT, POST, DELETE, PATCH }
