package dev.shinglem.hermes.component.backend

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.transport.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.cash.turbine.test
import dev.shinglem.hermes.component.labelsAsFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `BackendComponent` 的状态机（S5）。
 *
 * 这一页的主体是**外部流的投影**，所以重点是：
 * 1. 三条流任一变化都能反映到 state；
 * 2. 动作期间 busy=true，完成后归零；
 * 3. 失败时落到 notice 而不是崩；
 * 4. 诊断文本不含令牌。
 */
class BackendComponentTest {

    private class FakeGateway : BackendGateway {
        override val boot = MutableStateFlow<BackendBootState>(BackendBootState.Idle)
        override val logs = MutableStateFlow<List<BackendLogLine>>(emptyList())
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override var kind: BackendKind? = BackendKind.LOCAL

        var startFailure: Throwable? = null
        var startCalls = 0
        var stopCalls = 0
        var revalidateCalls = 0
        var reconnectCalls = 0

        override suspend fun start() {
            startCalls++
            startFailure?.let { throw it }
        }

        override suspend fun stop() {
            stopCalls++
        }

        override suspend fun revalidate() {
            revalidateCalls++
        }

        override fun reconnect() {
            reconnectCalls++
        }
    }

    private fun store(gateway: FakeGateway, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        dev.shinglem.hermes.component.backend.store.BackendStoreFactory.create(
            gateway = gateway,
            executorContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler),
        )

    @Test
    fun `初始状态反映 gateway 的当前值`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            assertEquals(BackendKind.LOCAL, s.state.kind)
            assertEquals(BackendBootState.Idle, s.state.boot)
            assertFalse(s.state.busy)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `外部流变化反映到 state`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            gateway.boot.value = BackendBootState.Starting("拉起进程")
            gateway.connection.value = ConnectionState.Connecting
            gateway.logs.value = listOf(BackendLogLine("stdout", "hello"))

            assertEquals(BackendBootState.Starting("拉起进程"), s.state.boot)
            assertEquals(ConnectionState.Connecting, s.state.connection)
            assertEquals(1, s.state.logs.size)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `Start 成功后 busy 归零并给出完成提示`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            s.accept(BackendIntent.Start)
            assertEquals(1, gateway.startCalls)
            assertFalse(s.state.busy)
            assertEquals("启动后端：完成", s.state.notice)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `动作失败落到 notice 而不是崩`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply { startFailure = IllegalStateException("端口被占") }
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            s.accept(BackendIntent.Start)
            assertFalse(s.state.busy, "失败也要把 busy 复位，否则按钮永久禁用")
            val notice = s.state.notice
            assertNotNull(notice)
            assertTrue(notice.contains("启动后端"), "提示要说明是哪个动作失败：$notice")
            assertTrue(notice.contains("端口被占"), "要带上原始原因：$notice")
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `DismissNotice 清掉提示`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            s.accept(BackendIntent.Start)
            assertNotNull(s.state.notice)
            s.accept(BackendIntent.DismissNotice)
            assertNull(s.state.notice)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `Reconnect 走 gateway 的 reconnect`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            s.accept(BackendIntent.Reconnect)
            assertEquals(1, gateway.reconnectCalls)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `诊断文本不含令牌`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        s.accept(BackendIntent.Observe) // 订阅外部流（Component 创建时自发一次）
        try {
            gateway.boot.value = BackendBootState.Ready(
                target = dev.shinglem.hermes.core.transport.GatewayTarget("http://127.0.0.1:9119", "SECRET-TOKEN"),
                instanceId = "inst",
                supervisor = "none",
                profileId = "default",
            )
            s.accept(BackendIntent.CopyDiagnostics)
            // Effect 走 labels，这里直接验证"状态里没有令牌"这条硬要求。
            assertFalse(
                s.state.apiOrigin?.contains("SECRET-TOKEN") == true,
                "apiOrigin 只含 baseUrl，不能带令牌",
            )
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `tail 截断到指定行数`() {
        val logs = (1..100).map { BackendLogLine("stdout", "line$it") }
        val state = BackendState(logs = logs)
        assertEquals(BackendState.DIAGNOSTIC_LOG_LINES, state.tail().size)
        assertEquals("line100", state.tail().last().text)
    }

    /** 一次性事件（Label）的发射序列用 Turbine 断言（验收 #12）。 */
    @Test
    fun `复制诊断信息发一次剪贴板事件`() = kotlinx.coroutines.test.runTest {
        val s = store(FakeGateway(), testScheduler)
        s.accept(BackendIntent.Observe)
        try {
            s.labelsAsFlow().test {
                s.accept(BackendIntent.CopyDiagnostics)
                val effect = awaitItem()
                assertTrue(effect is BackendEffect.CopyToClipboard, "应当只发剪贴板事件，实际 $effect")
                expectNoEvents()
            }
        } finally {
            s.dispose()
        }
    }
}