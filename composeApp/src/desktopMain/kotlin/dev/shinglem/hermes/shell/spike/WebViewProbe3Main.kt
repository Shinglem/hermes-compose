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
 * 探针：ADR-078 的 **S3（假 provider + 真实 Monaco）** 与 **S4（createDiffEditor）**。
 *
 * 素材在 `.local/webview-probe/`（**不入库**，4.41 MB）：
 * ```
 * .local/webview-probe/index.html   探针页面（假 HermesFsProvider + diff）
 * .local/webview-probe/vs/          裁剪后的 Monaco 资产（去掉 ts/css/html/json worker、
 *                                   高对比度主题、非中文语言包）
 * ```
 * 首次准备：
 * ```
 * npm install monaco-editor@0.57.0
 * # 拷 node_modules/monaco-editor/min/vs → .local/webview-probe/vs，然后删掉
 * #   vs/language/{typescript,css,html,json}/
 * #   vs/assets/{ts,css,html,json}.worker-*.js
 * #   vs/toggleHighContrast-*.js
 * #   vs/nls/lang/ 下只留 zh-cn.js
 * ```
 *
 * 回答三个问题：
 *  1. Monaco 的 `IFileSystemProvider` 在 `mtime = 0`、`size` 缺失时能否驱动 explorer（**S3**）；
 *  2. `createDiffEditor` 是否正常（**S4**）；
 *  3. 真实 Monaco 资产的加载体积增量（**S5** 的前半）。
 *
 * 结果写 `build/webview-probe3.txt`，完成即退出。
 */
fun main() {
    val out = File("build/webview-probe3.txt")
    out.parentFile?.mkdirs()
    out.writeText("")

    val lines = mutableListOf<String>()
    fun record(text: String) {
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    // workingDir 是 composeApp，素材在仓库根。用系统属性传绝对路径，避免相对路径歧义。
    val assetRoot = File(System.getProperty("hermes.probe.assets") ?: "../.local/webview-probe").canonicalFile
    if (!assetRoot.isDirectory) {
        record("[fatal] 缺少素材目录 ${assetRoot.absolutePath}（见文件头注释）")
        exitProcess(2)
    }
    val vsDir = File(assetRoot, "vs")
    record("=== composewebview probe 3 (S3: explorer / S4: diff) ===")
    record("[env] assetRoot=${assetRoot.absolutePath}")

    val vsFiles = vsDir.walkTopDown().filter { it.isFile }.toList()
    record("[assets] files=${vsFiles.size} bytes=${vsFiles.sumOf { it.length() }}")

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.address.port

    server.createContext("/") { ex ->
        val path = ex.requestURI.path.trimStart('/').ifEmpty { "index.html" }
        // 防目录穿越
        val target = File(assetRoot, path).canonicalFile
        if (!target.path.startsWith(assetRoot.canonicalPath) || !target.isFile) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return@createContext
        }
        val bytes = target.readBytes()
        ex.responseHeaders.add("Content-Type", contentTypeOf(target.name))
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.executor = null
    server.start()

    var exitCode = 0
    try {
        nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
            DecoratedWindow(
                onCloseRequest = {},
                state = rememberWindowState(),
                title = "Monaco Probe (S3/S4)",
            ) {
                val state = rememberWebViewState("http://127.0.0.1:$port/index.html")
                val navigator = rememberWebViewNavigator()
                val bridge = rememberWebViewJsBridge(navigator)

                LaunchedEffect(bridge) {
                    bridge.register(
                        object : IJsMessageHandler {
                            override fun methodName() = "probe3"
                            override fun handle(
                                message: JsMessage,
                                navigator: WebViewNavigator?,
                                callback: (String) -> Unit,
                            ) {
                                record("[js] " + message.params)
                                callback("""{"ok":true}""")
                            }
                        },
                    )
                }

                LaunchedEffect(Unit) {
                    record("[p0] navigated, loading=" + state.loadingState)
                    val finished = awaitFinished(state, 30_000)
                    record("[p0] finished=$finished url=${state.lastLoadedUrl}")
                    record("[p0] errors=${state.errorsForCurrentRequest.map { "${it.code}:${it.description}" }}")
                    // 给页面 20 秒跑完 monaco 加载 + worker + diff
                    delay(20_000)
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
        error.stackTraceToString().lineSequence().take(15).forEach { record("    $it") }
        exitCode = 2
    } finally {
        server.stop(0)
    }
    record("=== done (exit=$exitCode) ===")
    exitProcess(exitCode)
}

private suspend fun awaitFinished(state: WebViewState, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (state.loadingState !is LoadingState.Finished) delay(100)
        true
    } ?: false

private fun contentTypeOf(name: String): String = when {
    name.endsWith(".html") -> "text/html; charset=utf-8"
    name.endsWith(".js") -> "application/javascript; charset=utf-8"
    name.endsWith(".css") -> "text/css; charset=utf-8"
    name.endsWith(".json") -> "application/json; charset=utf-8"
    name.endsWith(".svg") -> "image/svg+xml"
    name.endsWith(".ttf") -> "font/ttf"
    name.endsWith(".woff") -> "font/woff"
    name.endsWith(".woff2") -> "font/woff2"
    else -> "application/octet-stream"
}
