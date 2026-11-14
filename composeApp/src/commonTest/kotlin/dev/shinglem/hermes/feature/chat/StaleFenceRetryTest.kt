package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `#14` 的「切模型不回归」判据（0.5.5 修过一次：切模型偶发失败，要点两次）。
 *
 * 根因是 `session.mutate` 带着过期 fence 被拒（`revision_conflict` / `stale_generation`），
 * 修法是「重新 resume 拿新 fence，再试一次」。这条判据决定**哪些拒绝值得重试**：
 *
 * - 值得：上面两条 —— 重新 resume 后确实会成功；
 * - 不值得：参数错 / 权限错 / 会话不存在 —— 重试结果一样，但会**延迟一轮**才把真正的
 *   错误暴露给用户，表现为"点了没反应，过一会儿才报错"。
 */
class StaleFenceRetryTest {

    @Test
    fun `两条 stale 原因都值得重试`() {
        assertTrue(shouldRetryOnStaleFence("revision_conflict"), "revision 冲突：重新 resume 会拿到新 revision")
        assertTrue(shouldRetryOnStaleFence("stale_generation"), "执行代过期：重新 resume 会拿到新 generation")
    }

    @Test
    fun `其余原因立刻抛给调用方`() {
        for (reason in listOf("invalid_params", "not_found", "forbidden", "session not found", "")) {
            assertFalse(shouldRetryOnStaleFence(reason), "$reason 不该重试，否则真正的错误被延迟一轮")
        }
    }

    /**
     * 没有 `reason` 时**不能重试**：`AppError.Server` 的 reason 来自后端 `data`，
     * 缺失说明响应形状异常 —— 这时重试等于盲猜，应当直接暴露。
     */
    @Test
    fun `没有 reason 时不重试`() {
        assertFalse(shouldRetryOnStaleFence(null))
    }

    /** 判据集合本身要稳定：多一条会让无关错误被重试，少一条会让切模型回到"要点两次"。 */
    @Test
    fun `判据集合正好是两条`() {
        assertTrue(
            STALE_FENCE_REASONS == setOf("revision_conflict", "stale_generation"),
            "实际 $STALE_FENCE_REASONS —— 改动这里要同步 CHANGELOG 里 0.5.5 的修复记录",
        )
    }
}
