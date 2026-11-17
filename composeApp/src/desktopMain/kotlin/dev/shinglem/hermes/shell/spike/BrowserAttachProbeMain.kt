package dev.shinglem.hermes.shell.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.NucleusPlatformView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * 探针：`NativeView` 能否嵌**另一个进程**的顶层窗口（v0.6.1 attach 方案的硬前提）。
 *
 * 与 v0.6.0 的 `WebViewProbe*` 不同 —— 那两个嵌的是本进程创建的 WebView2；
 * 这里嵌的是 **agent 自己拉起的 payload Chromium**，跨进程、窗口不属于我们。
 *
 * ### 怎么造被测对象
 *
 * 必须用**后端自己的 launcher** 起浏览器，而不是 `Start-Process chrome.exe`：
 * 只有 launcher 走的那条路才会带 `--remote-debugging-port=0` + 持久 profile，
 * 也才代表 agent 的真实形态。命令：
 *
 * ```
 * $py = 'release-test\hermes\tools\python-3.14.9+20261021-win32-x64\python.exe'
 * $env:PYTHONPATH = (Resolve-Path 'release-test\hermes\venv\Lib\site-packages').Path
 * # 见 build.gradle.kts 的 browserAttachProbe 任务，它会自己拉起
 * ```
 *
 * ### 验证什么
 *
 * | # | 判据 |
 * |---|---|
 * | A1 | `SetParent` 到本进程窗口后画面可见（不是黑块） |
 * | A2 | Compose 工具栏画在浏览器**之上**（DComp overlay 生效） |
 * | A3 | 窗口 resize 后子窗口跟随，不裂 |
 * | A4 | 关闭面板后**不杀**浏览器（它是 agent 的，`owned=false`） |
 *
 * 结果写 `build/browser-attach-probe.txt`，写完退出。
 *
 * ### 已知限制
 *
 * DPI 切换需要真实拖窗口到另一块不同缩放率的屏，自动化做不了 —— 这一项留给真机手验。
 */
fun main() {
    val out = File("build/browser-attach-probe.txt")
    out.parentFile?.mkdirs()
    out.writeText("")

    val lines = mutableListOf<String>()
    val record: (String) -> Unit = { text ->
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    val targetPid = System.getProperty("hermes.probe.pid")?.toLongOrNull()
    val targetHwnd = System.getProperty("hermes.probe.hwnd")?.toLongOrNull()
    record("=== browser attach probe (v0.6.1 attach) ===")
    record("[env] java=" + System.getProperty("java.version") + " pid=$targetPid hwnd=$targetHwnd")

    if (targetHwnd == null || targetHwnd == 0L) {
        record("[fatal] 没给 --hermes.probe.hwnd；先用后端 launcher 起浏览器再取 HWND")
        exitProcess(2)
    }

    try {
        nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
            DecoratedWindow(
                onCloseRequest = {},
                state = rememberWindowState(width = 1100.dp, height = 800.dp),
                title = "Browser Attach Probe",
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        // 工具栏：用来判 A2（Compose 能不能压在原生子窗口之上）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(Color(0xFF2B579A)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "  ▲ 这是一条 Compose 工具栏（应始终盖在浏览器之上）",
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        Box(Modifier.fillMaxSize()) {
                            BrowserSlot(targetHwnd, record)
                        }
                    }
                }
            }
        }
    } catch (error: Throwable) {
        record("[fatal] ${error::class.java.name}: ${error.message}")
        error.stackTraceToString().lineSequence().take(20).forEach { record("    $it") }
        exitProcess(2)
    }
    record("=== done ===")
    exitProcess(0)
}

@Composable
private fun BrowserSlot(hwnd: Long, record: (String) -> Unit) {
    var view by remember { mutableStateOf<NucleusPlatformView?>(null) }
    var disposeRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        record("[A0] composing NativeView for foreign hwnd=$hwnd")
        // 保持嵌入态，留足时间人工观察 + 截图（A1/A2/A3 都是视觉判据）
        val holdSeconds = System.getProperty("hermes.probe.holdS")?.toIntOrNull() ?: 90
        record("[hold] 嵌入态保持 ${holdSeconds}s；请观察：画面是否可见 / 工具栏是否盖住 / 拖动是否跟随")
        delay(holdSeconds * 1_000L)
        record("[A4] 即将销毁槽位；浏览器**不应**被杀（它是 agent 的）")
        val before = isWindowAlive(hwnd)
        record("[A4] 销毁前 hwnd 有效=$before")
        disposeRequested = true
        delay(3_000)
        val after = isWindowAlive(hwnd)
        record("[A4] 销毁后 hwnd 有效=$after（期望 true —— 槽位只管展示，不拥有进程）")
        record("=== done ===")
    }

    if (!disposeRequested) {
        NativeView(
            factory = {
                nucleusHwndPlatformView(handle = { hwnd }).also { created ->
                    record("[A0] platform view created: ${created::class.java.simpleName}")
                    record("[A1] window handle 绑定成功，画面应由 DComp 合成")
                    view = created
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF332B2B))) {
            Text("槽位已销毁（浏览器应仍在运行）", modifier = Modifier.padding(24.dp))
        }
    }

    DisposableEffect(Unit) {
        onDispose { record("[A4] slot dispose: ${view?.let { "view disposed" } ?: "no view"}") }
    }
}

private fun isWindowAlive(hwnd: Long): Boolean = Win32.isWindow(hwnd)

/** 只用来判「窗口还在不在」—— 槽位销毁不该影响外部进程。 */
private object Win32 {
    private val user32 = java.lang.foreign.SymbolLookup.libraryLookup("user32", java.lang.foreign.Arena.global())
    private val linker = java.lang.foreign.Linker.nativeLinker()

    private val isWindowHandle = runCatching {
        linker.downcallHandle(
            user32.find("IsWindow").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(
                java.lang.foreign.ValueLayout.JAVA_BOOLEAN,
                java.lang.foreign.ValueLayout.JAVA_LONG,
            ),
        )
    }.getOrNull()

    fun isWindow(hwnd: Long): Boolean {
        val handle = isWindowHandle ?: return false
        return runCatching { handle.invokeWithArguments(hwnd) as Boolean }.getOrDefault(false)
    }
}
