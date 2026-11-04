package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.result.Loadable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 审批 / clarify 必须按会话过滤：后端是"全局在等"，但界面只能画当前会话的，
 * 否则在 B 里看到 A 的审批会当成自己的事处理（用户实测反馈）。
 */
class ChatStateVisibilityTest {

    private val a = SessionKey(profile = null, sessionId = "a")
    private val b = SessionKey(profile = null, sessionId = "b")

    private fun approval(requestId: String, key: SessionKey?) = PendingApproval(
        requestId = requestId,
        command = "rm -rf /tmp/x",
        description = "",
        choices = listOf("once", "deny"),
        sessionKey = key,
    )

    private fun clarify(requestId: String, key: SessionKey?) = PendingClarify(
        requestId = requestId,
        frameId = "srq-$requestId",
        questions = listOf(ClarifyQuestion("q0", "继续吗？", listOf("是", "否"))),
        sessionKey = key,
    )

    @Test
    fun `approvals of other sessions are hidden`() {
        val state = ChatState(
            key = b,
            load = Loadable.Ready(emptyList()),
            pendingApprovals = listOf(approval("from-a", a), approval("from-b", b)),
        )
        assertEquals(listOf("from-b"), state.visibleApprovals.map { it.requestId })
        // 全局集合不受影响：后端还在等，切回 a 时要能看到。
        assertEquals(2, state.pendingApprovals.size)
    }

    @Test
    fun `approval without session key stays visible`() {
        // 认不出 runtime 时（sessionKey 为空）宁可显示：藏起来就永远没人处理，后端白等到超时。
        val state = ChatState(
            key = b,
            load = Loadable.Ready(emptyList()),
            pendingApprovals = listOf(approval("unknown", null)),
        )
        assertTrue(state.visibleApprovals.any { it.requestId == "unknown" })
    }

    @Test
    fun `clarify follows the same rule`() {
        val state = ChatState(
            key = a,
            load = Loadable.Ready(emptyList()),
            pendingClarify = listOf(clarify("from-a", a), clarify("from-b", b), clarify("unknown", null)),
        )
        assertEquals(listOf("from-a", "unknown"), state.visibleClarify.map { it.requestId })
    }

    /** 同一个 requestId 出现在别的 profile 下也必须挡掉：profile 参与 key。 */
    @Test
    fun `profile is part of the filter`() {
        val workKey = SessionKey(profile = "work", sessionId = "a")
        val state = ChatState(
            key = SessionKey(profile = "default", sessionId = "a"),
            load = Loadable.Ready(emptyList()),
            pendingApprovals = listOf(approval("from-work", workKey)),
        )
        assertTrue(state.visibleApprovals.isEmpty())
    }
}
