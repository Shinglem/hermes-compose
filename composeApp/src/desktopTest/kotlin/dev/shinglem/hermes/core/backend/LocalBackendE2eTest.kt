package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import dev.shinglem.hermes.core.log.Logging
import dev.shinglem.hermes.core.log.LogLevel
import dev.shinglem.hermes.core.transport.GatewayTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 端到端：用真实 payload 拉起 `serve` + `dashboard`，验证就绪握手与关停。
 *
 * 需要一份 sealed payload，默认跳过；本地跑：
 *
 * ```powershell
 * $env:HERMES_E2E_PAYLOAD = 'S:\mine\project\hermes-compose\hermes-core\win-bundle'
 * ./gradlew.bat :composeApp:desktopTest --tests "*LocalBackendE2eTest*"
 * ```
 */
class LocalBackendE2eTest {

    @Test
    fun `starts serve and dashboard then stops both`() = runBlocking {
        val payload = System.getenv("HERMES_E2E_PAYLOAD")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val work = File(System.getProperty("java.io.tmpdir"), "hermes-e2e-${System.nanoTime()}")
        val home = File(work, "home").absolutePath
        val data = File(work, "data").absolutePath
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Logging.init(File(data, "logs").absolutePath, LogLevel.WARN, alsoStderr = false)
        val logger = Logging.desktop

        val provider = LocalBackendProvider(
            payloadDir = payload,
            homeDir = home,
            dataDir = data,
            token = "e2e-token",
            nonce = "e2e",
            parentPid = ProcessHandle.current().pid(),
            parentStartMarker = "winms:${System.currentTimeMillis()}",
            local = LocalSection(readyTimeoutMs = 120_000),
            foreignHomes = LocalBackendPlanner.foreignHomes(
                System.getProperty("user.home").orEmpty(),
                System.getenv("APPDATA"),
                System.getenv("LOCALAPPDATA"),
            ),
            launcher = DesktopBackendLauncher(),
            fs = DesktopFsProbe,
            osEnv = System.getenv(),
            logger = logger,
            scope = scope,
        )

        try {
            provider.start()
            val ready = provider.state.value as? BackendBootState.Ready
                ?: error("启动失败：${provider.state.value}")

            val target = ready.target
            assertTrue(target.baseUrl.startsWith("http://127.0.0.1:"))
            assertTrue(portOf(target) > 0)

            // 归属标记必须落在我们自己的 home 里
            val marker = File(home, ".hermes-portable-owner")
            assertTrue(marker.isFile, "缺少归属标记")
            assertEquals(LocalBackendPlanner.normalize(payload), marker.readText().trim())

            // 控制台日志必须落盘（UI 只保留尾部 500 行，排查要看完整流）
            val serveLog = File(data, "logs/gateway.log")
            assertTrue(serveLog.isFile && serveLog.length() > 0, "serve 控制台日志未落盘")
            assertTrue(File(data, "logs/dashboard.log").length() > 0, "dashboard 控制台日志未落盘")

            provider.stop()
            assertEquals(BackendBootState.Idle, provider.state.value)

            // 关停必须真的把子进程收掉：端口不再可连。
            val servePort = portOf(target)
            assertTrue(waitUntilClosed(servePort), "serve 端口 $servePort 仍在监听，子进程未退出")
        } finally {
            provider.shutdown()
            scope.cancel()
            File(work.path).deleteRecursively()
        }
    }

    private fun portOf(target: GatewayTarget): Int =
        target.baseUrl.substringAfterLast(':').toIntOrNull() ?: 0

    private fun canConnect(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 800) }
        true
    }.getOrDefault(false)

    /** 关停是异步的（先 destroy、3s 后再强杀），端口释放要等一会儿。 */
    private fun waitUntilClosed(port: Int, timeoutMs: Long = 8_000): Boolean {
        if (port <= 0) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!canConnect(port)) return true
            Thread.sleep(200)
        }
        return !canConnect(port)
    }
}
