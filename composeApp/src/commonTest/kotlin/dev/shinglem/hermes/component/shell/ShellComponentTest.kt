package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.feature.shell.ProfileSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `ShellComponent` 的状态机（S5）。
 *
 * 重点是三条**时序敏感**的分支（迁移前在 `ShellViewModel` 的 `init` 里，
 * 用真后端很难造出那些窗口）：
 * 1. 连上后自动拉 profile；
 * 2. 本地后端就绪后主动重连一次（不是每次）；
 * 3. profile 被删后回落到 default。
 */
class ShellComponentTest {

    private class FakeGateway : ShellGateway {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override var backendKind: BackendKind = BackendKind.LOCAL
        override val backendState = MutableStateFlow<BackendBootState>(BackendBootState.Idle)
        override val backendLogs = MutableStateFlow<List<BackendLogLine>>(emptyList())
        override val gatewayProbe = MutableStateFlow<ProbeState>(ProbeState.Idle)

        var profiles: List<ProfileSummary> = emptyList()
        var profilesFailure: Throwable? = null
        var loadCalls = 0
        var reconnectCalls = 0
        var persisted: String? = "unset"
        var reconcileCalls = 0

        override suspend fun loadProfiles(): List<ProfileSummary> {
            loadCalls++
            profilesFailure?.let { throw it }
            return profiles
        }

        override fun persistActiveProfile(name: String?) {
            persisted = name
        }

        override suspend fun startBackend() = Unit

        override fun reconnect() {
            reconnectCalls++
        }

        override suspend fun reconcileBrowserArgsOnce() {
            reconcileCalls++
        }
    }

    private fun store(gateway: FakeGateway, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, active: String? = null) =
        dev.shinglem.hermes.component.shell.store.ShellStoreFactory.create(
            gateway = gateway,
            executorContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler),
            initial = ShellState(activeProfile = active),
        )

    private fun profile(name: String) = ProfileSummary(
        name = name,
        displayName = name,
        model = null,
        provider = null,
        isDefault = name == "default",
        skillCount = 0,
    )

    @Test
    fun `连上后自动拉 profile`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply { profiles = listOf(profile("default"), profile("work")) }
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            assertEquals(0, gateway.loadCalls, "未连接时不该拉")
            gateway.connection.value = ConnectionState.Connected()
            assertEquals(1, gateway.loadCalls, "连上后应自动拉一次")
            assertEquals(2, s.state.profilesOrEmpty.size)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `后端就绪后只主动重连一次`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.backendState.value = BackendBootState.Ready(
                dev.shinglem.hermes.core.transport.GatewayTarget("http://127.0.0.1:1", "t"),
                "i", "none", "default",
            )
            assertEquals(1, gateway.reconnectCalls)
            // 后续再变（例如日志更新）不该重复重连 —— 否则会陷入"重连→状态变→再重连"。
            gateway.backendLogs.value = listOf(BackendLogLine("stdout", "x"))
            assertEquals(1, gateway.reconnectCalls, "只该重连一次")
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `profile 被删后回落到 default 并写回偏好`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply { profiles = listOf(profile("default")) }
        val s = store(gateway, testScheduler, active = "gone")
        try {
            s.accept(ShellIntent.Observe)
            gateway.connection.value = ConnectionState.Connected()
            assertEquals(null, gateway.persisted, "应把 profile 写回 null（表示 default）")
            assertNull(s.state.activeProfile)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `profile 存在时不写偏好`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply { profiles = listOf(profile("default"), profile("work")) }
        val s = store(gateway, testScheduler, active = "work")
        try {
            s.accept(ShellIntent.Observe)
            gateway.connection.value = ConnectionState.Connected()
            assertEquals("unset", gateway.persisted, "profile 仍在时不该改写偏好")
            assertEquals("work", s.state.activeProfile)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `加载失败保留旧列表`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply { profilesFailure = IllegalStateException("boom") }
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.connection.value = ConnectionState.Connected()
            assertTrue(s.state.profiles is dev.shinglem.hermes.core.result.Loadable.Failed, "失败要落到 Loadable.Failed")
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `选择 profile 同时切回会话页`() = kotlinx.coroutines.test.runTest {
        // 迁移前的语义：点档案 = 去这个档案的会话（ADR-044）。
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.OpenSettings)
            assertEquals(ShellMode.SETTINGS, s.state.mode)
            s.accept(ShellIntent.SelectProfile("work"))
            assertEquals("work", s.state.activeProfile)
            assertEquals(ShellMode.SESSIONS, s.state.mode, "点档案要回到会话页")
            assertEquals("work", gateway.persisted)
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `选择 default 归一化成 null`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.SelectProfile(ShellState.DEFAULT_PROFILE))
            assertNull(s.state.activeProfile, "default 在内部表示为 null（与迁移前一致）")
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `浏览器参数对账只做一次`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.connection.value = ConnectionState.Connected()
            gateway.connection.value = ConnectionState.Connecting
            gateway.connection.value = ConnectionState.Connected()
            assertEquals(1, gateway.reconcileCalls, "对账改的是持久配置，不该反复做")
        } finally {
            s.dispose()
        }
    }

    @Test
    fun `Reducer 已加载时不退回 Loading`() {
        val reducer = dev.shinglem.hermes.component.shell.store.ShellReducer()
        val loaded = ShellState(profiles = dev.shinglem.hermes.core.result.Loadable.Ready(listOf(profile("a"))))
        val after = reducer.run { loaded.reduce(ShellResult.ProfilesLoading) }
        assertTrue(after.profilesOrEmpty.isNotEmpty(), "已有列表时刷新不该让界面闪空白")
    }
}
