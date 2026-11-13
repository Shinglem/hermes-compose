package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.LogLevel
import dev.shinglem.hermes.domain.model.SessionSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

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
        // 装上 ContentNegotiation（v0.5.x 声明了依赖但从未 install，导致全仓库 54 处手写
        // `(x as? JsonPrimitive)?.intOrNull`）。`ignoreUnknownKeys` 必须开：
        // 后端新增字段时不能炸；`isLenient` 不开，保持契约严格。
        install(ContentNegotiation) {
            json(RestJson, ContentType.Application.Json)
        }
    }

    /**
     * 反序列化直取：`getAs("/api/foo", FooListSerializer)`。
     *
     * 与 [get] 的区别：那个给的是 `JsonElement`（需要手写取值），这个是 DTO 直出。
     * 新代码优先用它；老代码按阶段迁移（S3）。
     *
     * 用显式 serializer 而不是 `inline reified`：`commonMain` 里 `decodeFromJsonElement`
     * 需要 `DeserializationStrategy`，而 inline reified 版本要把 `RestJson` 泄漏成
     * `@PublishedApi`，反而增加公开面。
     */
    suspend fun <T> getAs(
        path: String,
        deserializer: DeserializationStrategy<T>,
        timeoutMs: Long = Timeouts.DEFAULT_MS,
    ): T = RestJson.decodeFromJsonElement(deserializer, get(path, timeoutMs))

    /** 写请求 + 反序列化结果（无 body 的写请求传对应 DTO 的 serializer）。 */
    suspend fun <T> sendAs(
        method: RestMethod,
        path: String,
        deserializer: DeserializationStrategy<T>,
        body: JsonObject? = null,
        timeoutMs: Long = Timeouts.WRITE_MS,
    ): T = RestJson.decodeFromJsonElement(deserializer, send(method, path, body, timeoutMs))

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

    /**
     * 二进制下载（备份 zip 等）：不走 JSON 解析。
     *
     * 错误映射与 [parse] 一致（401/403 → [AppError.Unauthorized]，4xx/5xx → [AppError.Server]）。
     */
    suspend fun download(path: String, timeoutMs: Long = Timeouts.CATALOG_MS): ByteArray {
        val target = targetProvider()
        val url = target.baseUrl.trim().trimEnd('/') + path
        try {
            return withTimeout(timeoutMs) {
                val response = http.get(url) {
                    target.token?.takeIf { it.isNotBlank() }?.let { header(SESSION_HEADER_NAME, it) }
                }
                when {
                    response.status == HttpStatusCode.Unauthorized ||
                        response.status == HttpStatusCode.Forbidden -> throw AppError.Unauthorized
                    response.status.value >= 400 -> throw AppError.Server(
                        response.status.value,
                        extractErrorMessage(response.bodyAsText()),
                    )
                    else -> response.bodyAsBytes()
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AppError.Timeout(path)
        } catch (error: AppError) {
            throw error
        } catch (throwable: Throwable) {
            logger.log(LogLevel.WARN, "REST download $path failed: ${throwable.message}", throwable)
            throw ErrorMapper.from(throwable, path)
        }
    }

    private fun parse(status: HttpStatusCode, body: String, path: String): JsonElement = when {        status == HttpStatusCode.Unauthorized ||
            status == HttpStatusCode.Forbidden -> throw AppError.Unauthorized

        status.value >= 400 -> throw AppError.Server(status.value, extractErrorMessage(body))

        body.isBlank() -> JsonObject(emptyMap())

        else -> RpcJson.json.parseToJsonElement(body)
    }

    /** `/api/status` 是唯一免鉴权端点，用来做健康探测与后端信息展示。 */
    /**
     * 会话列表：**走 REST `/api/sessions`**，不是 RPC `session.list`。
     *
     * 0.23.0 的 `session.list` 多了 `scope: Literal['live','stored'] = 'live'` ——
     * 默认只返回**运行中**的会话，历史会话要显式 `scope=stored`。官方 Desktop 的做法是
     * 直接读 REST（`apps/desktop/src/api/sessions.ts::listSessions`），这里跟随它：
     * REST 还顺带给了分页、归档过滤与排序，语义更完整。
     */
    suspend fun listSessions(
        profile: String?,
        limit: Int = 40,
        offset: Int = 0,
        archived: String = "exclude",
        order: String = "recent",
        timeoutMs: Long = Timeouts.DEFAULT_MS,
    ): List<SessionSummary> {
        val query = buildString {
            // `/api/sessions` 的 `limit` 上限是 100（超过直接 422 `less_than_equal`），
            // 而上下文/置顶的取样会想要更多 —— 这里统一 clamp，调用方不必各自记得。
            append("/api/sessions?limit=").append(limit.coerceIn(1, MAX_LIMIT))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&min_messages=0")
            append("&archived=").append(archived)
            append("&order=").append(order)
            profile?.takeIf { it.isNotBlank() }?.let { append("&profile=").append(it.encodeURLParameter()) }
        }
        val element = get(query, timeoutMs)
        val rows = (element as? JsonObject)?.get("sessions") as? JsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull { (it as? JsonObject)?.toSessionSummary() }
    }

    private fun JsonObject.toSessionSummary(): SessionSummary? {
        val id = (this["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val startedAt = (this["started_at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return SessionSummary(
            id = id,
            title = (this["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            preview = (this["preview"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            startedAt = Instant.fromEpochSeconds(startedAt.toLong()),
            messageCount = (this["message_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            source = (this["source"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

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
     * 只探这一个端点：客户端唯一的后端是官方 gateway，JSON-RPC / REST 都在这台 listener 上。
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

        /** `/api/sessions` 的 `limit` 硬上限（上游 `Query(le=100)`），超过即 422。 */
        const val MAX_LIMIT = 100

        /**
         * REST 用的 JSON：与 `RpcJson` 同一套宽松策略（后端会加字段），
         * `encodeDefaults = true` 保证写请求把零值也带上。
         */
        val RestJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = false
            explicitNulls = false
        }
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
