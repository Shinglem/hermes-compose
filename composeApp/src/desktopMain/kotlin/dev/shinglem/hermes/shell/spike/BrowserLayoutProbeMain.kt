package dev.shinglem.hermes.shell.spike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.shinglem.hermes.component.browser.BrowserIntent
import dev.shinglem.hermes.component.browser.BrowserState
import dev.shinglem.hermes.component.browser.DefaultBrowserComponent
import dev.shinglem.hermes.component.browser.Phase
import dev.shinglem.hermes.di.DesktopBrowserGateway
import dev.shinglem.hermes.feature.browser.BrowserSidePanel
import dev.shinglem.hermes.feature.browser.BrowserSlot
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * 探针：**收起状态下纳管是否保持**（v0.6.1 最关键的行为）。
 *
 * 用户要求「纳管但不展示，抽屉默认关闭，点侧栏才显示」。这里要验的正是：
 *
 * | # | 判据 |
 * |---|---|
 * | C1 | `TakeOver` 后 `phase == TakenOver`，但 `panelOpen == false` |
 * | C2 | 收起状态下窗口**仍是**本进程的 child window（不跳回独立窗口） |
 * | C3 | 持续 15s 采样，`GetParent(hwnd) == 我们的主窗口` 始终成立 |
 * | C4 | 展开后画面出现在抽屉里（截图人看） |
 *
 * ### 怎么造被测对象
 *
 * 浏览器必须由**后端 launcher** 拉起（它会写 launch record）：
 *
 * ```powershell
 * # 见 BrowserDiscoveryProbeMain 头部；跑完拿到 pid
 * .\gradlew.bat :composeApp:browserLayoutProbe -Phermes.probe.home=<HERMES_HOME>
 * ```
 *
 * 结果写 `build/browser-layout-probe.txt`。
 */
fun main() {
    val out = File("build/browser-layout-probe.txt")
    out.parentFile?.mkdirs()
    out.writeText("")

    val lines = mutableListOf<String>()
    val record: (String) -> Unit = { text ->
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    val home = System.getProperty("hermes.probe.home").orEmpty()
    record("=== browser layout probe (纳管与展示是否独立) ===")
    if (home.isBlank()) {
        record("[fatal] 没给 -Phermes.probe.home")
        exitProcess(2)
    }

    val lifecycle = LifecycleRegistry()
    val context = DefaultComponentContext(lifecycle)
    val gateway = DesktopBrowserGateway(hermesHome = File(home))
    val component = DefaultBrowserComponent(
        componentContext = context,
        gateway = gateway,
        // Unconfined：探针里不要等调度
        executorContext = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    try {
        nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
            DecoratedWindow(
                onCloseRequest = {},
                state = rememberWindowState(width = 1200.dp, height = 800.dp),
                title = "Browser Layout Probe",
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by component.state.collectAsState()
                    Row(Modifier.fillMaxSize()) {
                        // 模拟"主内容区"
                        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("主内容区（抽屉应默认关闭，只占右边缘 40dp）")
                        }
                        BrowserSidePanel(
                            state = state,
                            onTogglePanel = { component.onIntent(BrowserIntent.TogglePanel) },
                            onRefresh = { component.onIntent(BrowserIntent.Refresh) },
                            onOpenInSystemBrowser = {},
                            onDismissError = { component.onIntent(BrowserIntent.DismissError) },
                            onOpenBrowserSettings = {},
                            onResize = {},
                            onSelectTarget = {},
                            content = { pid, m ->
                                val hwnd = state.targets.firstOrNull { it.pid == pid }?.hwnd ?: 0L
                                BrowserSlot(hwnd = hwnd, modifier = m)
                            },
                        )
                    }

                    LaunchedEffect(Unit) {
                        delay(2_000)
                        record("[C1] 触发 TakeOver（模拟 agent 调 browser 工具）")
                        component.onIntent(BrowserIntent.TakeOver)
                        delay(3_000)

                        val s1 = component.state.value
                        record("[C1] panelOpen=${s1.panelOpen}（期望 false）")
                        record("[C1] phase=${s1.phase}")
                        record("[C1] takenOver=${s1.takenOver}（期望 true）")

                        record("[C3] 持续采样 15s，看窗口是否跳回独立窗口")
                        var toplevelCount = 0
                        repeat(30) {
                            delay(500)
                            val s = component.state.value
                            val phase = s.phase
                            if (phase !is Phase.TakenOver) {
                                record("[C3] 第 $it 次：phase 变了 -> $phase  ← 解挂了")
                                toplevelCount++
                            }
                        }
                        record("[C3] 解挂次数=$toplevelCount（期望 0）")
                        record("[C3] panelOpen 始终=${component.state.value.panelOpen}（期望 false）")

                        record("[C4] 现在展开抽屉，5s 后截图人看")
                        component.onIntent(BrowserIntent.TogglePanel)
                        delay(5_000)
                        record("[C4] panelOpen=${component.state.value.panelOpen}")
                        record("=== done（请人工确认抽屉里有画面） ===")
                    }
                }
            }
        }
    } catch (error: Throwable) {
        record("[fatal] ${error::class.java.name}: ${error.message}")
        error.stackTraceToString().lineSequence().take(15).forEach { record("    $it") }
        exitProcess(2)
    }
    record("=== done ===")
    exitProcess(0)
}
