package dev.shinglem.hermes.component.notification

import dev.shinglem.hermes.feature.chat.SessionKey
import dev.shinglem.hermes.platform.NotificationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知策展（S5）。
 *
 * 这一层原来埋在 `AppShell` 的局部闭包里，错误表现是"该弹没弹 / 弹了两遍" ——
 * 用户很难复述，排查成本极高。现在每条判据都能单独断言。
 */
class NotificationComponentTest {

    private fun component(enabled: Boolean = true, supported: Boolean = true) =
        DefaultNotificationComponent(enabled = enabled, supported = supported)

    private val key = SessionKey(profile = "default", sessionId = "s1")
    private val actions = listOf(NotificationAction("允许一次") {}, NotificationAction("拒绝") {})

    @Test
    fun `开关关闭时不发通知`() = runTest {
        val c = component(enabled = false)
        c.remindApproval("r1", key, "t", "b", actions, foreground = false, selectedSessionId = null, onScreen = false)
        val got = withTimeoutOrNull(200) { c.effects.toList() }
        assertTrue(got.isNullOrEmpty(), "开关关闭时不该有任何 Effect")
    }

    @Test
    fun `平台不支持时不发通知`() = runTest {
        val c = component(supported = false)
        c.remindApproval("r1", key, "t", "b", actions, foreground = false, selectedSessionId = null, onScreen = false)
        val got = withTimeoutOrNull(200) { c.effects.toList() }
        assertTrue(got.isNullOrEmpty())
    }

    @Test
    fun `后台时提醒`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        c.remindApproval("r1", key, "t", "b", actions, foreground = false, selectedSessionId = "s1", onScreen = true)
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(1, received.size, "窗口在后台一律提醒，即使卡片在界面上")
    }

    @Test
    fun `前台且正看着这个会话时不提醒`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        c.remindApproval("r1", key, "t", "b", actions, foreground = true, selectedSessionId = "s1", onScreen = false)
        kotlinx.coroutines.yield()
        job.cancel()
        assertTrue(received.isEmpty(), "前台且选中同一会话时不该弹")
    }

    @Test
    fun `前台但卡片已在界面上时不提醒`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        c.remindApproval("r1", key, "t", "b", actions, foreground = true, selectedSessionId = null, onScreen = true)
        kotlinx.coroutines.yield()
        job.cancel()
        assertTrue(received.isEmpty())
    }

    @Test
    fun `同一请求只提醒一次`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        repeat(3) {
            c.remindApproval("r1", key, "t", "b", actions, foreground = false, selectedSessionId = null, onScreen = false)
        }
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(1, received.size, "重连补拉 / 快照恢复会把同一条再送一遍，不能重复提醒")
    }

    @Test
    fun `forget 之后可以再次提醒`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        c.remindApproval("r1", key, "t", "b", actions, foreground = false, selectedSessionId = null, onScreen = false)
        c.forget("approval:r1")
        c.remindApproval("r1", key, "t", "b", actions, foreground = false, selectedSessionId = null, onScreen = false)
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(2, received.size)
    }

    @Test
    fun `正文截断到 180 字`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        c.remindTurnFinished("turn:1", key, "已完成", "x".repeat(500), foreground = false, selectedSessionId = null)
        kotlinx.coroutines.yield()
        job.cancel()
        val notify = received.single() as NotificationEffect.Notify
        assertEquals(180, notify.body.length, "通知正文要截断，否则系统气泡显示不全")
    }

    @Test
    fun `定位不到会话时宁可提醒`() = runTest {
        val c = component()
        val received = mutableListOf<NotificationEffect>()
        val job = backgroundScope.launch { c.effects.collect { received += it } }
        kotlinx.coroutines.yield() // 让收集器先订阅上（SharedFlow 的 tryEmit 不等待订阅者）
        c.remindTurnFinished("turn:1", target = null, "已完成", "b", foreground = true, selectedSessionId = "s1")
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(1, received.size, "事件没带 session_id 时宁可提醒也别漏")
    }
}