package dev.shinglem.hermes.platform

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 通知队列语义：多条审批都要能被看到，且不能同时压一堆窗口。
 *
 * 这一层曾是单槽（`post()` 直接覆盖），第二个审批到达就把第一个顶掉 ——
 * 第一个既没人看见、`ReminderGate` 又记过 id，于是永远不再提醒。
 */
class NotificationInboxTest {

    @BeforeTest
    fun clearBefore() {
        NotificationInbox.dismissAll()
    }

    @AfterTest
    fun clearAfter() {
        NotificationInbox.dismissAll()
    }

    private fun post(title: String) = NotificationInbox.post(
        title = title,
        body = "命令 $title",
        actions = listOf(NotificationAction("允许一次") {}, NotificationAction("拒绝") {}),
    )

    @Test
    fun `三条审批都能显示`() {
        val a = post("A")
        val b = post("B")
        val c = post("C")
        assertEquals(listOf(a, b, c), NotificationInbox.active.value.map { it.id })
        assertEquals(listOf(a, b, c), NotificationInbox.queue.value.map { it.id })
    }

    @Test
    fun `id 递增`() {
        val first = post("A")
        val second = post("B")
        assertTrue(second > first)
    }

    @Test
    fun `第五次投递只有前三条同屏`() {
        val ids = (1..5).map { post("审批 $it") }
        assertEquals(NotificationInbox.MAX_VISIBLE, NotificationInbox.active.value.size)
        assertEquals(ids.take(3), NotificationInbox.active.value.map { it.id })
        assertEquals(ids, NotificationInbox.queue.value.map { it.id })
    }

    @Test
    fun `关掉一条后排队的补位`() {
        val ids = (1..5).map { post("审批 $it") }
        // 关掉当前第一条（最早的那条）
        NotificationInbox.dismiss(ids[0])
        assertEquals(listOf(ids[1], ids[2], ids[3]), NotificationInbox.active.value.map { it.id })
        assertEquals(4, NotificationInbox.queue.value.size)
    }

    @Test
    fun `只移除对应那条`() {
        val ids = (1..3).map { post("审批 $it") }
        // 关中间那条：另外两条必须还在，且顺序不变
        NotificationInbox.dismiss(ids[1])
        assertEquals(listOf(ids[0], ids[2]), NotificationInbox.active.value.map { it.id })
    }

    @Test
    fun `自动收起互不干扰`() {
        val ids = (1..3).map { post("审批 $it") }
        // 模拟最早那条到时：只剩它被移除，其它两条仍在
        NotificationInbox.dismiss(ids[0])
        assertEquals(2, NotificationInbox.active.value.size)
        assertTrue(NotificationInbox.active.value.none { it.id == ids[0] })
    }

    @Test
    fun `同一个 id 重复投递被忽略`() {
        val request = NotificationRequest(id = 9001, title = "重复", body = "体")
        NotificationInbox.post(request)
        NotificationInbox.post(request)
        assertEquals(1, NotificationInbox.queue.value.size)
    }

    @Test
    fun `关掉不存在的 id 不改动队列`() {
        val ids = (1..2).map { post("审批 $it") }
        NotificationInbox.dismiss(-1)
        assertEquals(ids, NotificationInbox.queue.value.map { it.id })
    }

    @Test
    fun `dismissAll 清空队列与同屏`() {
        post("A")
        post("B")
        NotificationInbox.dismissAll()
        assertTrue(NotificationInbox.queue.value.isEmpty())
        assertTrue(NotificationInbox.active.value.isEmpty())
    }
}
