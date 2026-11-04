package dev.shinglem.hermes.core.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 把传输层异常收敛成 [AppError]。
 *
 * 刻意不 import 平台 IO 异常类型（`commonMain` 不能碰 `java.*`）：
 * 连接类失败用消息特征兜底，传输层自己会先给出更精确的分类。
 */
object ErrorMapper {
    fun from(throwable: Throwable, operation: String): AppError = when (throwable) {
        is AppError -> throwable
        is TimeoutCancellationException -> AppError.Timeout(operation)
        is CancellationException -> throw throwable
        else -> {
            val message = throwable.message.orEmpty()
            when {
                looksLikeAuthFailure(message) -> AppError.Unauthorized
                looksLikeConnectionFailure(message) -> AppError.BackendUnreachable
                else -> AppError.Unknown(message.ifBlank { throwable::class.simpleName ?: "error" })
            }
        }
    }

    private fun looksLikeConnectionFailure(message: String): Boolean {
        val text = message.lowercase()
        return "connection refused" in text ||
            "connect timed out" in text ||
            "failed to connect" in text ||
            "unable to connect" in text ||
            "network is unreachable" in text ||
            "connection reset" in text ||
            "broken pipe" in text
    }

    /** Ktor 握手失败的措辞是 `The server returned status code '403' when status code '101' was expected.` */
    private fun looksLikeAuthFailure(message: String): Boolean {
        val text = message.lowercase()
        return "status code '401'" in text || "status code '403'" in text
    }
}
