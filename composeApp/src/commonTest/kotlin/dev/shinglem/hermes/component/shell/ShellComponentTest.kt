package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.BackendLogLine
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.transport.BackendHealth
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.feature.shell.ProfileSummary
import dev.shinglem.hermes.feature.shell.toPreference
import dev.shinglem.hermes.feature.shell.toShellModeForShellComponent
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
        var probeCalls = 0
        var healthCalls = 0
        var persistedMode: ShellMode? = null
        override val health = MutableStateFlow<BackendHealth?>(null)

        override suspend fun loadProfiles(): List<ProfileSummary> {
            loadCalls++
            profilesFailure?.let { throw it }
            return profiles
        }

        override fun persistActiveProfile(name: String?) {
            persisted = name
        }

        override fun persistMode(mode: ShellMode) {
            persistedMode = mode
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

    // ── S8 回归：三条迁移漏项 ────────────────────────────────────────

    /**
     * 迁移前的 `ShellViewModel.startHeartbeat()` 每 5s 探一次 `/api/status`。
     * 新实现一度把 `_probe` 初始化后**再没有任何写入点**，导致状态栏网关 chip 恒为 `—`，
     * 且「WS 还活着但 HTTP 已死」这个唯一的半死检测消失。
     *
     * 探活循环在 `RealShellGateway` 里（放 Executor 会让 `runTest` 永不 idle），
     * 这里的断言是「gateway 的 probe 流会被合并进 state」。
     */
    @Test
    fun `gateway 的探活结果会合进 state`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.gatewayProbe.value = ProbeState.Ok(7)
            assertEquals(ProbeState.Ok(7), s.state.gatewayProbe, "状态栏的网关 chip 靠它")
        } finally {
            s.dispose()
        }
    }

    /** `/api/health` 的结果要落到 state（状态栏与关于页的版本号靠它）。 */
    @Test
    fun `health 结果写进 state`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.health.value = BackendHealth(
                version = "0.23.0",
                authRequired = false,
                gatewayRunning = true,
                profiles = emptyList(),
            )
            assertEquals("0.23.0", s.state.health?.version, "关于页与状态栏要显示后端版本")
        } finally {
            s.dispose()
        }
    }

    /** health 失败（返回 null）时不能把已有版本号刷成空。 */
    @Test
    fun `health 失败保留上一次的值`() {
        val reducer = dev.shinglem.hermes.component.shell.store.ShellReducer()
        val loaded = ShellState(
            health = BackendHealth("0.23.0", false, true, emptyList()),
        )
        val after = reducer.run {
            loaded.reduce(
                ShellResult.ExternalSnapshot(
                    connection = ConnectionState.Idle,
                    backendKind = BackendKind.LOCAL,
                    backend = BackendBootState.Idle,
                    backendLogs = emptyList(),
                    gatewayProbe = ProbeState.Idle,
                    health = null,
                ),
            )
        }
        assertEquals("0.23.0", after.health?.version, "探活失败不该把版本号清空")
    }

    /**
     * 迁移前 `profilesLoaded = true` 在 `try` 成功后置位；新实现一度放在请求**之前**，
     * 于是"启动时后端还没就绪 → `profiles.list` 失败"会让该进程**永不再加载** profile，
     * 而 UI 没有手动刷新入口 —— profile rail 空到重启。
     */
    @Test
    fun `profile 加载失败后下一次连接会重试`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply {
            profilesFailure = IllegalStateException("backend not ready")
        }
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.connection.value = ConnectionState.Connected()
            assertEquals(1, gateway.loadCalls)

            // 后端就绪、连接抖动一次：必须重试，而不是把状态钉死。
            gateway.profilesFailure = null
            gateway.profiles = listOf(profile("default"))
            gateway.connection.value = ConnectionState.Connecting
            gateway.connection.value = ConnectionState.Connected()

            assertEquals(2, gateway.loadCalls, "失败后应重试，否则 profile rail 空到重启")
            assertEquals(1, s.state.profilesOrEmpty.size)
        } finally {
            s.dispose()
        }
    }

    /** 成功加载后不该反复拉（否则每次连接抖动都打一次 REST）。 */
    @Test
    fun `profile 加载成功后不再重复拉`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway().apply { profiles = listOf(profile("default")) }
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.Observe)
            gateway.connection.value = ConnectionState.Connected()
            gateway.connection.value = ConnectionState.Connecting
            gateway.connection.value = ConnectionState.Connected()
            assertEquals(1, gateway.loadCalls, "成功后不该重复拉")
        } finally {
            s.dispose()
        }
    }

    /**
     * 迁移前 `openCapabilities()` 写 `preferences.mode`、`openSettings()` 不写。
     * 新实现一度两者都不写，且 `preferences.mode` 唯一的写入点是 `persistActiveProfile`
     * 里那句强制 `MODE_SESSIONS` —— 结果能力页重启后永远回不去。
     */
    @Test
    fun `打开能力页写偏好 打开设置页不写`() = kotlinx.coroutines.test.runTest {
        val gateway = FakeGateway()
        val s = store(gateway, testScheduler)
        try {
            s.accept(ShellIntent.OpenCapabilities)
            assertEquals(ShellMode.CAPABILITIES, s.state.mode)
            assertEquals(
                ShellMode.CAPABILITIES,
                gateway.persistedMode,
                "能力页是常驻去处，重启应回到这里",
            )

            gateway.persistedMode = null
            s.accept(ShellIntent.OpenSettings)
            assertEquals(ShellMode.SETTINGS, s.state.mode)
            assertNull(gateway.persistedMode, "设置页是一次性去处，不该写偏好")
        } finally {
            s.dispose()
        }
    }

    /** 模式枚举与偏好字符串的双向映射：只有 CAPABILITIES 会被持久化。 */
    @Test
    fun `模式与偏好字符串的映射`() {
        assertEquals("capabilities", ShellMode.CAPABILITIES.toPreference())
        assertEquals("sessions", ShellMode.SESSIONS.toPreference())
        assertEquals("sessions", ShellMode.SETTINGS.toPreference(), "设置页重启应回到会话")
        assertEquals(ShellMode.CAPABILITIES, "capabilities".toShellModeForShellComponent())
        assertEquals(ShellMode.SESSIONS, "sessions".toShellModeForShellComponent())
    }
}
