package dev.shinglem.hermes.core.error

import kotlinx.serialization.json.JsonElement

/**
 * 领域错误。传输层异常一律在 [ErrorMapper] 里收敛成它，UI 层再映射成文案。
 *
 * 继承 [Exception] 是为了能在挂起函数里 `throw`；同时它是 `sealed`，
 * 让 UI 的 `when` 必须穷尽。这里不放中文文案：文案属于 Compose Resources。
 */
sealed class AppError(message: String) : Exception(message) {
    /** 连不上后端（`127.0.0.1:9119` 无监听 / 网络不可达）。 */
    data object BackendUnreachable : AppError("backend unreachable")

    /** 401 / 403：token 缺失或不匹配。 */
    data object Unauthorized : AppError("unauthorized")

    /** 请求发出时 WebSocket 未就绪。 */
    data object NotConnected : AppError("not connected")

    data class Timeout(val operation: String) : AppError("timeout: $operation")

    /** 收到无法解析的帧、或协议层字段缺失。 */
    data class Protocol(val detail: String) : AppError("protocol: $detail")

    /** 服务端 JSON-RPC 错误对象。`data` 是后端的结构化补充（fence 新鲜值、非法字段名等）。 */
    data class Server(val code: Int, val reason: String, val data: JsonElement? = null) :
        AppError("server $code: $reason")

    data class Unknown(val summary: String) : AppError("unknown: $summary")
}

/** 供日志与诊断页使用的单行描述，不含用户可见文案。 */
fun AppError.technicalSummary(): String = when (this) {
    AppError.BackendUnreachable -> "backend unreachable"
    AppError.Unauthorized -> "unauthorized"
    AppError.NotConnected -> "not connected"
    is AppError.Timeout -> "timeout: $operation"
    is AppError.Protocol -> "protocol: $detail"
    is AppError.Server -> "server $code: $reason"
    is AppError.Unknown -> "unknown: $summary"
}
