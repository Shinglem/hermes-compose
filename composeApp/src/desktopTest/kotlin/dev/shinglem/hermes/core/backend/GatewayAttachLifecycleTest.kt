package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.LogLevel
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 真实 payload 上的生命周期验证：`ensure` 起 daemon → attach → `shutdown` 把它停掉。
 *
 * 会起一个真实的后端进程（十几秒），所以**没有 payload 就跳过**：CI 的 windows 镜像
 * 不带 3 GB 的 release-test 目录。本机可用 `-Dhermes.test.payload=<dir>` 指定别的位置。
 *
 * 这条测试守的是 isolated 路线**没有**的那个风险：daemon 脱离 Job Object 之后，
 * 「客户端关掉、后端还在跑」只能靠 `gateway stop` 兜住。
 */
class GatewayAttachLifecycleTest {

    private val payload: File by lazy {
        val explicit = System.getProperty("hermes.test.payload")?.takeIf { it.isNotBlank() }
        if (explicit != null) File(explicit)
        else File(System.getProperty("user.dir")).let { cwd ->
            // 测试的 working dir 是模块目录（composeApp），仓库根在上一级。
            listOf(
                File(cwd, "release-test/hermes"),
                File(cwd.parentFile ?: cwd, "release-test/hermes"),
            ).firstOrNull { File(it, "bin/hermes.exe").isFile } ?: File(cwd, "release-test/hermes")
        }
    }

    private val skipReason: String? by lazy {
        when {
            !File(payload, "bin/hermes.exe").isFile -> "没有 payload：${payload.absolutePath}"
            !File(payload, "manifest.json").isFile -> "payload 不是 sealed 形态：${payload.absolutePath}"
            else -> null
        }
    }

    private object SilentLogger : AppLogger {
        override fun log(level: LogLevel, message: String, throwable: Throwable?) = Unit
        override val logFilePath: String = ""
    }

    @Test
    fun `ensure attach 之后 shutdown 会把本 home 的 gateway 停掉`() {
        skipReason?.let {
            println("[skip] GatewayAttachLifecycleTest: $it")
            return
        }
        val home = Files.createTempDirectory("hermes-gw-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = GatewayAttachBackendProvider(
            payloadDir = payload.absolutePath,
            homeDir = home.absolutePath,
            token = "it-token-" + System.nanoTime(),
            local = LocalSection(runCron = false, readyTimeoutMs = 60_000),
            foreignHomes = emptyList(),
            runner = DesktopOneShotRunner,
            fs = DesktopFsProbe,
            osEnv = System.getenv(),
            logger = SilentLogger,
            scope = scope,
        )
        var baseUrl = ""
        try {
            runBlocking { provider.start() }
            val state = provider.state.value
            assertIs<BackendBootState.Ready>(state, "attach 失败：$state")
            baseUrl = state.target.baseUrl
            assertTrue(baseUrl.startsWith("http://"), "baseUrl 应来自 endpoint.api_origin：$baseUrl")
            assertTrue(!baseUrl.endsWith(":0"), "attach 之后不该还是占位地址：$baseUrl")
            assertEquals(200, httpStatus("$baseUrl/api/status"), "attach 之后 /api/status 应可达")

            provider.shutdown()
            assertTrue(
                waitUntilGone(baseUrl, timeoutMs = 20_000),
                "shutdown 之后 daemon 仍在监听 $baseUrl（gateway stop 没生效）",
            )
        } finally {
            runCatching { provider.shutdown() }
            scope.cancel()
            // daemon 已经停了；目录里是它写的数据，直接删。
            runCatching { home.deleteRecursively() }
        }
    }

    private fun httpStatus(url: String): Int = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(-1)

    private fun waitUntilGone(baseUrl: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (httpStatus("$baseUrl/api/status") !in 200..299) return true
            Thread.sleep(300)
        }
        return false
    }
}
