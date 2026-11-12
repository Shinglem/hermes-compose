package dev.shinglem.hermes.core.backend

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 探测一个 CDP 端点是否真的有人监听。
 *
 * 用**裸 TCP connect** 而不是 HTTP 请求：`/json/version` 在某些 Chrome 版本上对
 * `HEAD` 返回 405、对默认 profile 返回 404，而那些情况下端点其实是活的 —— 用状态码判定会误清配置。
 * 端口能建立连接就说明"那里有东西在听"，这已经足够支撑"要不要清掉 cdp_url"的决定。
 */
object CdpProbe {
    private const val TIMEOUT_MS = 400

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cdp-probe").apply { isDaemon = true }
    }

    /** `http://127.0.0.1:9222` / `ws://[::1]:9222/devtools/...` → 端口可连则为 true。 */
    fun isReachable(url: String): Boolean {
        val hostPort = parseHostPort(url) ?: return false
        val future = executor.submit(
            Callable {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(hostPort.first, hostPort.second), TIMEOUT_MS)
                        true
                    }
                }.getOrDefault(false)
            },
        )
        return runCatching { future.get(TIMEOUT_MS.toLong() * 2, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
    }

    /**
     * 从 URL 里取 `(host, port)`；没有端口时按 scheme 补默认值（`ws` → 80、`wss` → 443）。
     * 解析不出来返回 null —— 调用方据此保持原配置不动。
     */
    internal fun parseHostPort(url: String): Pair<String, Int>? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val normalized = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) ||
                raw.startsWith("ws://", true) || raw.startsWith("wss://", true) -> raw
            // 裸 `127.0.0.1:9222` 这种写法，补个 scheme 才能被 URI 解析。
            else -> "http://$raw"
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: when (uri.scheme?.lowercase()) {
            "https", "wss" -> 443
            else -> 80
        }
        return host to port
    }
}
