package dev.shinglem.hermes.feature.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.backend.BackendKind
import dev.shinglem.hermes.core.backend.ProbeState
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.transport.BackendHealth
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.GatewayTarget
import dev.shinglem.hermes.ui.theme.HermesTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StatusBarTest {
    private val profile = ProfileSummary(
        name = "default",
        displayName = "",
        model = "deepseek-v4.1-flash",
        provider = "volcengine",
        isDefault = true,
        skillCount = 58,
    )

    private fun state(connection: ConnectionState) = ShellState(
        profiles = Loadable.Ready(listOf(profile)),
        connection = connection,
        health = BackendHealth(
            version = "0.21.5",
            authRequired = false,
            gatewayRunning = true,
            profiles = listOf("default"),
        ),
    )

    @Test
    fun `连接成功时显示已连接与后端版本，不显示模型`() = runComposeUiTest {
        setContent {
            HermesTheme { StatusBar(state(ConnectionState.Connected("epoch")), onRetryConnection = {}) }
        }
        onNodeWithText("已连接").assertIsDisplayed()
        onNodeWithText("后端 0.21.5").assertIsDisplayed()
        // 模型改由 composer 的 pill 显示（ADR-038），状态条不再重复。
        onNodeWithText("deepseek-v4.1-flash").assertDoesNotExist()
    }

    @Test
    fun `重连中显示次数`() = runComposeUiTest {
        setContent {
            HermesTheme { StatusBar(state(ConnectionState.Reconnecting(3, 4_000)), onRetryConnection = {}) }
        }
        onNodeWithText("重连中（第 3 次）").assertIsDisplayed()
    }

    @Test
    fun `配置要求管理员但没拿到时显示普通权限`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connected("epoch")),
                    onRetryConnection = {},
                    showUnelevatedHint = true,
                )
            }
        }
        onNodeWithText("普通权限").assertIsDisplayed()
    }

    @Test
    fun `没要求或已提权时不显示普通权限`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connected("epoch")),
                    onRetryConnection = {},
                    showUnelevatedHint = false,
                )
            }
        }
        onNodeWithText("普通权限").assertDoesNotExist()
    }

    @Test
    fun `断线时提供重试入口`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Disconnected(AppError.BackendUnreachable)),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("未连接").assertIsDisplayed()
        onNodeWithContentDescription("重试").assertIsDisplayed()
    }

    @Test
    fun `本地后端启动中显示当前步骤`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connecting).copy(
                        backendKind = BackendKind.LOCAL,
                        backend = BackendBootState.Starting("启动后端（dashboard）"),
                    ),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("网关 启动后端（dashboard）").assertIsDisplayed()
    }

    @Test
    fun `本地后端就绪显示心跳而不是端口`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connected("epoch")).copy(
                        backendKind = BackendKind.LOCAL,
                        backend = BackendBootState.Ready(GatewayTarget("http://127.0.0.1:57586")),
                        gatewayProbe = ProbeState.Ok(11),
                    ),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("网关 11ms").assertIsDisplayed()
    }

    @Test
    fun `后端启动失败时状态栏给出失败文案`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Disconnected(AppError.BackendUnreachable)).copy(
                        backendKind = BackendKind.LOCAL,
                        backend = BackendBootState.Failed("未找到内嵌 Hermes payload", "缺 manifest.json"),
                    ),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("网关 失败").assertIsDisplayed()
    }

    @Test
    fun `网关与服务心跳分开显示`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connected("epoch")).copy(
                        backendKind = BackendKind.LOCAL,
                        backend = BackendBootState.Ready(
                            GatewayTarget("http://127.0.0.1:57586"),
                            dashboardUrl = "http://127.0.0.1:57586",
                        ),
                        gatewayProbe = ProbeState.Ok(12),
                        dashboardProbe = ProbeState.Ok(9),
                    ),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("网关 12ms").assertIsDisplayed()
        onNodeWithText("服务 9ms").assertIsDisplayed()
    }

    @Test
    fun `服务挂了不影响网关的显示`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connected("epoch")).copy(
                        backendKind = BackendKind.LOCAL,
                        backend = BackendBootState.Ready(
                            GatewayTarget("http://127.0.0.1:57586"),
                            dashboardUrl = "http://127.0.0.1:57586",
                        ),
                        gatewayProbe = ProbeState.Ok(8),
                        dashboardProbe = ProbeState.Fail("Connection refused"),
                    ),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("网关 8ms").assertIsDisplayed()
        onNodeWithText("服务 无响应").assertIsDisplayed()
    }

    @Test
    fun `远端模式不显示服务一项`() = runComposeUiTest {
        setContent {
            HermesTheme {
                StatusBar(
                    state = state(ConnectionState.Connected("epoch")).copy(
                        backendKind = BackendKind.REMOTE,
                        gatewayProbe = ProbeState.Ok(5),
                    ),
                    onRetryConnection = {},
                )
            }
        }
        onNodeWithText("网关 5ms").assertIsDisplayed()
        onNodeWithText("服务 —").assertDoesNotExist()
    }
}
