package dev.shinglem.hermes.di

import dev.shinglem.hermes.component.browser.BrowserGateway
import dev.shinglem.hermes.component.browser.BrowserTarget
import dev.shinglem.hermes.core.browser.BrowserDiscovery
import dev.shinglem.hermes.core.browser.DiscoveryOutcome
import java.io.File

/**
 * [BrowserGateway] 的桌面实现：把 `BrowserDiscovery` 包成 component 层认识的样子。
 *
 * ### 为什么在读 URL 上要自己发 HTTP 而不是用 `RestClient`
 *
 * `/json/list` 在**那只浏览器自己的随机端口**上，不是一个固定后端地址；`RestClient`
 * 带着后端的 baseUrl 与鉴权头，用它反而要绕。这里只需要最朴素的一次 GET。
 *
 * ### 线程
 *
 * `discover()` 与 `activePageUrl()` 都是阻塞 IO（读目录 / 读文件 / HTTP）。
 * Component 侧已经把 executor 放在 `Dispatchers.IO`，这里**不要**再加 `withContext`
 * —— 多一层调度只会让"取消"变难。
 */
class DesktopBrowserGateway(
    /** 后端（本程序独占）的 `HERMES_HOME`，records 在它下面。 */
    hermesHome: File,
) : BrowserGateway {

    private val discovery = BrowserDiscovery(hermesHome = hermesHome)

    override suspend fun discoverAll(): BrowserGateway.DiscoveryResult {
        return when (val outcome = discovery.discover()) {
            is DiscoveryOutcome.Found -> BrowserGateway.DiscoveryResult(
                targets = outcome.targets.map {
                    BrowserTarget(pid = it.pid, hwnd = it.hwnd, port = it.port)
                },
            )
            is DiscoveryOutcome.Headless -> BrowserGateway.DiscoveryResult(headlessPid = outcome.pid)
            DiscoveryOutcome.NoRecord -> BrowserGateway.DiscoveryResult()
            DiscoveryOutcome.Unsupported -> BrowserGateway.DiscoveryResult(unsupported = true)
        }
    }

    override suspend fun activePageUrl(port: Int): String? {
        if (port <= 0) return null
        return runCatching {
            val url = java.net.URI("http://127.0.0.1:$port/json/list")
            val connection = url.toURL().openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseActiveUrl(body)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** 判活（非 suspend：底层两个 Win32 调用）。 */
    override fun isSlotAlive(hwnd: Long, pid: Long): Boolean =
        dev.shinglem.hermes.core.browser.Win32WindowQuery.isAliveForPid(hwnd = hwnd, pid = pid)

    /** 只列目录，不读文件 —— 心跳短路用，必须廉价。 */
    override fun hasAnyRecord(): Boolean = discovery.hasAnyRecord()

    private fun parseActiveUrl(json: String): String? = runCatching {
        val arr = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray
            ?: return null
        // `/json/list` 返回所有 target；优先取 type == "page" 的第一条，
        // 没有 page 就退第一条有 url 的。
        val objects = arr.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        val page = objects.firstOrNull {
            (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "page"
        } ?: objects.firstOrNull()
        (page?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf(String::isNotBlank)
    }.getOrNull()

    private companion object {
        const val TIMEOUT_MS = 800
    }
}
