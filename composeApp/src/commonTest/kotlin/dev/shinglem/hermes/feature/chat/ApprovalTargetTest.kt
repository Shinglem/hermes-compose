package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `#19` 的「对话-审批」行：应答目标怎么取（S8 从 `ChatViewModel.resolveApproval` 抽出的判据）。
 *
 * 这条判据有一个**实测踩过的坑**：用户在**另一个会话**里点系统通知上的「允许一次」时，
 * 如果现查 `_state.value.key` / 当前 runtime，拿到的是另一个会话的值 ——
 * 后端回 `4001 stale_generation`（实测：切会话后点通知必报）。
 *
 * 所以顺序必须是「卡片自己记的 → **同一会话**的兜底 → 放弃」。注意兜底也只能用
 * 卡片所属会话的值，不能跨会话借用。
 */
class ApprovalTargetTest {

    private fun key(id: String) = SessionKey(null, id)

    private fun pending(
        requestId: String = "req-1",
        sessionKey: SessionKey? = null,
        runtimeSessionId: String? = null,
        executionGeneration: Int? = null,
    ) = PendingApproval(
        requestId = requestId,
        command = "rm -rf /",
        description = "危险命令",
        choices = listOf("once", "deny"),
        runtimeSessionId = runtimeSessionId,
        fromRequest = false,
        sessionKey = sessionKey,
        executionGeneration = executionGeneration,
    )

    @Test
    fun `卡片自带全部信息时原样使用`() {
        val target = approvalTarget(
            pending = pending(sessionKey = key("a"), runtimeSessionId = "rt-a", executionGeneration = 7),
            fallbackKey = key("b"),
            fallbackRuntimeId = "rt-b",
            fallbackGeneration = 99,
        )
        assertEquals(key("a"), target?.key, "会话必须是卡片记的，不是当前打开的")
        assertEquals("rt-a", target?.runtimeId)
        assertEquals(7, target?.generation, "fence 也必须是卡片记的，否则 4001")
    }

    /**
     * **本轮最关键的一条**：跨会话点通知时，卡片没带 runtimeId 也**不能**借当前会话的。
     * 借了就是拿 B 的 runtime 去答 A 的请求 —— 后端直接 4001，且用户完全看不出原因。
     */
    @Test
    fun `跨会话且卡片缺 runtimeId 时放弃而不是借用当前会话`() {
        val target = approvalTarget(
            pending = pending(sessionKey = key("a")),
            fallbackKey = key("b"),
            fallbackRuntimeId = "rt-b",
            fallbackGeneration = 99,
        )
        assertNull(target, "跨会话时借当前会话的 runtime 必然 4001，应当放弃")
    }

    @Test
    fun `同会话缺 runtimeId 时用当前会话兜底`() {
        val target = approvalTarget(
            pending = pending(sessionKey = key("a")),
            fallbackKey = key("a"),
            fallbackRuntimeId = "rt-a",
            fallbackGeneration = 3,
        )
        assertEquals("rt-a", target?.runtimeId)
        assertEquals(3, target?.generation)
    }

    @Test
    fun `卡片没记会话时用当前会话兜底`() {
        val target = approvalTarget(
            pending = pending(),
            fallbackKey = key("a"),
            fallbackRuntimeId = "rt-a",
            fallbackGeneration = 3,
        )
        assertEquals(key("a"), target?.key)
        assertEquals("rt-a", target?.runtimeId)
    }

    @Test
    fun `既没卡片会话也没当前会话时放弃`() {
        assertNull(approvalTarget(pending(), fallbackKey = null, fallbackRuntimeId = null, fallbackGeneration = null))
    }

    @Test
    fun `拿不到 runtimeId 时放弃`() {
        assertNull(
            approvalTarget(
                pending = pending(sessionKey = key("a")),
                fallbackKey = key("a"),
                fallbackRuntimeId = null,
                fallbackGeneration = 3,
            ),
        )
    }

    /** generation 允许为空：后端在部分路径上不强制要求（`approval.respond` 的 fence 是 optional）。 */
    @Test
    fun `generation 缺失仍然可以应答`() {
        val target = approvalTarget(
            pending = pending(sessionKey = key("a"), runtimeSessionId = "rt-a"),
            fallbackKey = key("a"),
            fallbackRuntimeId = "rt-a",
            fallbackGeneration = null,
        )
        assertEquals("rt-a", target?.runtimeId)
        assertNull(target?.generation)
    }
}
