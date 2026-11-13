package dev.shinglem.hermes.core.error

/**
 * 任意异常 → 用户可见中文提示。
 *
 * 统一入口，替代各 feature 里逐份复制的 `Throwable.describe()`（v0.5.x 有 7 份，逐字同构）。
 * 规则：
 *  1. `AppError` 走 [toNotice]（有业务文案）；
 *  2. 其它异常退回 `message`，再退回按类型名的兜底 —— 绝不返回空串（UI 会当成"没出错"）。
 *
 * **不要**用它替代日志：日志用 [technicalSummary]，它给的是给人看的一句话。
 */
fun Throwable.toUserNotice(): String = when (this) {
    is AppError -> toNotice()
    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误（${this::class.simpleName}）"
}

/**
 * 带操作名的版本：非 `AppError` 先经 [ErrorMapper] 收敛，再出文案。
 *
 * `operation` 是 RPC 方法名或 REST 路径（例：`session.mutate`），
 * 会出现在超时类文案里，方便用户/日志定位是哪一步失败。
 */
fun Throwable.toUserNotice(operation: String): String =
    (this as? AppError ?: ErrorMapper.from(this, operation)).toNotice()

/**
 * 把任意异常收敛成 [AppError]，供需要结构化错误的调用点使用。
 *
 * 替代各 feature 里逐份复制的 `Throwable.asXxxError()`（v0.5.x 有 3 份）。
 */
fun Throwable.asAppError(operation: String = "unknown"): AppError =
    this as? AppError ?: AppError.Unknown(message.orEmpty().ifBlank { operation })
