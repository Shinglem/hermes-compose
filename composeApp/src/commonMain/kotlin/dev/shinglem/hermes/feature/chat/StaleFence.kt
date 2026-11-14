package dev.shinglem.hermes.feature.chat

/**
 * fence 过期的拒绝原因（`gateway/session_controls.py` 的两条）。
 *
 * 抽到独立文件是为了能被测试直接引用 —— 它决定「`session.mutate` 被拒后要不要重新
 * resume 再试一次」，判错的表现是：**该重试的不重试**（改模型时偶发失败、用户再点一次才好）
 * 或**不该重试的乱重试**（真正的权限/参数错误被吞掉，只剩一次多余的 resume）。
 */
internal val STALE_FENCE_REASONS: Set<String> = setOf("revision_conflict", "stale_generation")

/**
 * 这次拒绝值不值得「重新 resume 拿新 fence 再试一次」。
 *
 * 判据只有一条：原因在 [STALE_FENCE_REASONS] 里。其余（参数错、权限错、会话不存在）
 * 重试也不会有不同结果，必须原样抛给调用方 —— 静默重试会让真正的错误延迟一轮才暴露。
 */
internal fun shouldRetryOnStaleFence(reason: String?): Boolean =
    reason != null && reason in STALE_FENCE_REASONS
