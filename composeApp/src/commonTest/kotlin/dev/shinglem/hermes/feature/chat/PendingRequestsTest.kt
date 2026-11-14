package dev.shinglem.hermes.feature.chat

import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S8 · 「等客户端应答」的请求表（原 `approvalDecisions` + `approvalFrameIds` 四张平行表）。
 *
 * 重点断「成对」：迁移前每次登记要写两张表、摘除要删两张，少一处就是
 * "卡片点了没反应"或"`request.cancel` 撤不掉"，且两种症状都不报错。
 */
class PendingRequestsTest {

    private fun deferred(): CompletableDeferred<String> = CompletableDeferred()

    @Test
    fun `按业务 id 与帧 id 都能查到`() {
        val table = PendingRequests<String>()
        val decision = deferred()
        table.register(PendingRequest("frame-1", "req-1", decision))

        assertEquals(decision, table.decisionOf("req-1"))
        assertEquals("req-1", table.businessIdOf("frame-1"))
        assertEquals(1, table.size)
    }

    @Test
    fun `按业务 id 摘除同时清掉帧索引`() {
        val table = PendingRequests<String>()
        table.register(PendingRequest("frame-1", "req-1", deferred()))

        assertNotNull(table.removeByRequestId("req-1"))
        assertNull(table.decisionOf("req-1"))
        assertNull(table.businessIdOf("frame-1"), "帧索引必须跟着摘，否则 cancel 会指向一条已删记录")
        assertEquals(0, table.size)
    }

    @Test
    fun `按帧 id 摘除同时清掉业务索引`() {
        val table = PendingRequests<String>()
        table.register(PendingRequest("frame-1", "req-1", deferred()))

        assertNotNull(table.removeByFrameId("frame-1"))
        assertNull(table.businessIdOf("frame-1"))
        assertNull(table.decisionOf("req-1"))
    }

    @Test
    fun `摘不存在的条目是空操作`() {
        val table = PendingRequests<String>()
        assertNull(table.removeByRequestId("nope"))
        assertNull(table.removeByFrameId("nope"))
        assertEquals(0, table.size)
    }

    /** 同 requestId 重复登记（重连补拉会重复拿到同一条）：后者覆盖，表不膨胀。 */
    @Test
    fun `重复登记覆盖旧句柄`() {
        val table = PendingRequests<String>()
        val first = deferred()
        val second = deferred()
        table.register(PendingRequest("frame-1", "req-1", first))
        table.register(PendingRequest("frame-1", "req-1", second))

        assertEquals(1, table.size)
        assertEquals(second, table.decisionOf("req-1"))
    }

    /**
     * `takeDecisionByFrameId` **不移除记录**：撤回后等待方自己会走 `finally` 摘除，
     * 提前摘会让它的 resolve 变成空操作（真出过"撤回后按钮还在"）。
     */
    @Test
    fun `按帧取句柄不摘记录`() {
        val table = PendingRequests<String>()
        val decision = deferred()
        table.register(PendingRequest("frame-1", "req-1", decision))

        assertEquals(decision, table.takeDecisionByFrameId("frame-1"))
        assertEquals(1, table.size, "取句柄不该顺手摘记录")
    }

    @Test
    fun `按业务 id 完成会摘掉记录`() {
        val table = PendingRequests<String>()
        val decision = deferred()
        table.register(PendingRequest("frame-1", "req-1", decision))

        assertTrue(table.completeByRequestId("req-1", "deny"))
        assertTrue(decision.isCompleted)
        assertEquals(0, table.size)
        assertFalse(table.completeByRequestId("req-1", "again"), "第二次完成应返回 false")
    }

    @Test
    fun `cancelAll 取消所有等待并清空`() {
        val table = PendingRequests<String>()
        val a = deferred()
        val b = deferred()
        table.register(PendingRequest("f1", "r1", a))
        table.register(PendingRequest("f2", "r2", b))

        table.cancelAll()

        assertTrue(a.isCancelled)
        assertTrue(b.isCancelled)
        assertEquals(0, table.size)
        assertNull(table.businessIdOf("f1"))
    }
}
