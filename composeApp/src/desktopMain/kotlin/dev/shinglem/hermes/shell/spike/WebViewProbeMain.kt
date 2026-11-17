package dev.shinglem.hermes.shell.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.rememberWindowState
import com.sun.net.httpserver.HttpServer
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.webview.jsbridge.IJsMessageHandler
import dev.nucleusframework.webview.jsbridge.JsMessage
import dev.nucleusframework.webview.jsbridge.rememberWebViewJsBridge
import dev.nucleusframework.webview.web.LoadingState
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.WebViewState
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import java.io.File
import java.net.InetSocketAddress
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 探针：Nucleus 官方 WebView（`dev.nucleusframework:composewebview`）在 Tao 3.0 下能否承载 Monaco。
 *
 * 对应 ADR-078 的 S1 / S2，结论已定（2026-11-16，Windows 11 / JDK 27 / WebView2 157.0.4341.46）：
 *
 * | 项 | 结论 |
 * |---|---|
 * | S2 `composewebview 1.0.3` × Nucleus `3.0.0-dev` | ✅ 窗口能开、WebView 能显示、`kmpJsBridge` 正常注入与回传 |
 * | `data:` 源（`WebContent.Data` / `loadHtml`） | ❌ `origin=null`：`fetch` 失败、`new Worker(http)` 抛异常；只有 blob worker 能用 |
 * | `loadHtml(baseUrl = …)` | ❌ `baseUrl` 被忽略（`origin` 仍是 `null`）；且 `loadHtml` 行为依赖上下文 —— 三次不导航、一次生效，不可依赖 |
 * | **`WebContent.Url("http://127.0.0.1:<port>/…")`** | ✅ 真源：`fetch` 同源 ok、blob worker ok、8 并发 worker 全 ok |
 *
 * 因此宿主页方案是：**自起 loopback http 服务（Ktor CIO，端口 0）+ 反向代理 `/api/fs`**。
 * 后端 CORS 是白名单制（`_LocalOriginCORSMiddleware`），我们的随机端口不在内 —— 不能直连 fetch。
 * 详见 `.design/详细设计/功能/07-文件与编辑器.md` §7.1。
 *
 * 本探针保留作为「换 Nucleus 版本 / 换 WebView2 运行时」时的回归判据。
 * 结果写 `build/webview-probe.txt`，写完即退出。
 */
fun main() {
    val out = File("build/webview-probe.txt")
    out.parentFile?.mkdirs()
    out.writeText("")

    val lines = mutableListOf<String>()
    fun record(text: String) {
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    val markers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.address.port
    server.createContext("/x.js") { ex ->
        val body = "window.__probeHit = (window.__probeHit ? window.__probeHit + 1 : 1);".toByteArray()
        ex.responseHeaders.add("Content-Type", "application/javascript; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.createContext("/api/ping") { ex ->
        val body = """{"ok":true}""".toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.createContext("/host.html") { ex ->
        val body = hostHtml().toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.executor = null
    server.start()

    record("=== composewebview probe (ADR-078 S1/S2) ===")
    record("[env] java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + " port=$port")

    try {
        nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
            DecoratedWindow(
                onCloseRequest = {},
                state = rememberWindowState(),
                title = "WebView Probe",
            ) {
                val state = rememberWebViewState("http://127.0.0.1:$port/host.html")
                val navigator = rememberWebViewNavigator()
                val bridge = rememberWebViewJsBridge(navigator)

                LaunchedEffect(bridge) {
                    bridge.register(
                        object : IJsMessageHandler {
                            override fun methodName() = "probe"
                            override fun handle(
                                message: JsMessage,
                                navigator: WebViewNavigator?,
                                callback: (String) -> Unit,
                            ) {
                                record("[js] " + message.params)
                                Regex(""""marker":"([^"]+)"""").find(message.params)
                                    ?.groupValues?.getOrNull(1)?.let { markers += it }
                                callback("""{"ok":true}""")
                            }
                        },
                    )
                }

                LaunchedEffect(Unit) {
                    record("[S2] window composed, webView null=" + (state.webView == null))
                    record("[p1] http host finished=" + awaitFinished(state, 20_000))
                    record("[p1] lastUrl=" + state.lastLoadedUrl)
                    record("[p1] errors=" + state.errorsForCurrentRequest.map { "${it.code}:${it.description}" })
                    delay(8_000)

                    // loadHtml 边界（结论：不真正导航），仅作回归记录
                    navigator.loadHtml(buildHtml("SIZE_1MB", 1024 * 1024))
                    val finished = awaitFinished(state, 15_000)
                    val reported = withTimeoutOrNull(5_000) {
                        while ("SIZE_1MB" !in markers) delay(100)
                        true
                    } ?: false
                    record("[p2] loadHtml 1MB finished=$finished reported=$reported lastUrl=${state.lastLoadedUrl?.take(12)}")

                    record("=== done ===")
                    exitProcess(0)
                }

                WebView(state = state, navigator = navigator, webViewJsBridge = bridge, modifier = Modifier.fillMaxSize())
                @Suppress("UNUSED_EXPRESSION")
                Text("", Modifier.fillMaxSize(0f))
            }
        }
    } catch (error: Throwable) {
        record("[fatal] ${error::class.java.name}: ${error.message}")
        error.stackTraceToString().lineSequence().take(12).forEach { record("    $it") }
        record("=== done (fatal) ===")
        exitProcess(2)
    } finally {
        server.stop(0)
    }
    record("=== done (window closed) ===")
}

private suspend fun awaitFinished(state: WebViewState, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (state.loadingState !is LoadingState.Finished) delay(100)
        true
    } ?: false

private fun buildHtml(marker: String, totalBytes: Int): String {
    val head = "<!doctype html><html><body><pre id=\"p\">"
    val tail = "</pre><script>(function(){var n=document.getElementById('p').textContent.length;" +
        "function r(){if(window.kmpJsBridge&&window.kmpJsBridge.callNative){" +
        "window.kmpJsBridge.callNative('probe',JSON.stringify({marker:'$marker',chars:n}),function(){});return;}" +
        "setTimeout(r,100);}r();})();</script></body></html>"
    val pad = (totalBytes - head.length - tail.length).coerceAtLeast(0)
    return head + "A".repeat(pad) + tail
}

/** 真源下的能力矩阵：fetch 同源、同源 script、8 并发 blob worker。 */
private fun hostHtml(): String = """
<!doctype html>
<html><body>
<script>
(function () {
  var r = { origin: String(location.origin), proto: location.protocol };
  function report() {
    if (window.kmpJsBridge && window.kmpJsBridge.callNative) {
      try { window.kmpJsBridge.callNative('probe', JSON.stringify(r), function(){}); return; } catch (e) {}
    }
    setTimeout(report, 200);
  }

  r.fetchSame = 'pending';
  fetch('/api/ping').then(function (x) { return x.text(); })
    .then(function (t) { r.fetchSame = 'ok:' + t; })
    .catch(function (e) { r.fetchSame = 'fail:' + e.name + ':' + e.message; });

  r.scriptSame = 'pending';
  var s = document.createElement('script');
  s.src = '/x.js';
  s.onload = function () { r.scriptSame = 'loaded hit=' + String(window.__probeHit); };
  s.onerror = function () { r.scriptSame = 'error'; };
  document.head.appendChild(s);

  r.workers = [];
  var pending = 8;
  function step() { if (--pending === 0) report(); }
  for (var i = 0; i < 8; i++) {
    (function (idx) {
      var st = { idx: idx, state: 'pending' };
      r.workers.push(st);
      try {
        var u = URL.createObjectURL(new Blob(['self.postMessage("w" + ' + idx + ')'], { type: 'text/javascript' }));
        var w = new Worker(u);
        var settled = false;
        w.onmessage = function (e) { if (!settled) { settled = true; st.state = 'ok:' + String(e.data); step(); } };
        w.onerror = function (e) { if (!settled) { settled = true; st.state = 'error:' + String(e.message || ''); step(); } };
        setTimeout(function () { if (!settled) { settled = true; st.state = 'timeout'; step(); } }, 3000);
      } catch (e) {
        st.state = 'throw:' + e.message;
        step();
      }
    })(i);
  }

  setTimeout(report, 6000);
})();
</script>
</body></html>
""".trimIndent()
