package dev.shinglem.hermes.shell.spike

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.shinglem.hermes.platform.UiDispatcher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlin.system.exitProcess

/**
 * 验收探针：修复后「UI 事件来源线程」与「UiDispatcher 线程」是否同一条。
 *
 * 背景：S6 迁 Nucleus Tao 后 `Dispatchers.Main`（Tao `main`）与 `Dispatchers.Swing`
 * （`AWT-EventQueue-0`）分家。`DesktopContainer` 曾用 Swing，导致
 * GatewayClient / ViewModel 与 UI 回调跨线程共享非线程安全状态。
 *
 * 用 `./gradlew :composeApp:taoSwingProbe` 运行，结果落在 `build/swing-probe.txt`。
 */
fun main() {
    val out = File("build/swing-probe.txt")
    out.parentFile?.mkdirs()
    val lines = mutableListOf<String>()

    fun record(text: String) {
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    record("=== UiDispatcher acceptance probe ===")
    record("UiDispatcher.context = ${UiDispatcher.context}")

    // 模拟 DesktopContainer.build() 里创建的 scope：启动前创建、长期持有。
    val containerScope = CoroutineScope(SupervisorJob() + UiDispatcher.context)
    containerScope.launch {
        delay(2_000)
        record("[container] resumed-after-start thread=${Thread.currentThread().name}")
    }

    nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
        DecoratedWindow(
            onCloseRequest = {},
            state = rememberWindowState(),
            title = "UiDispatcher Probe",
        ) {
            LaunchedEffect(Unit) {
                record("[compose] thread=${Thread.currentThread().name}")

                containerScope.launch {
                    record("[container] new-launch thread=${Thread.currentThread().name}")
                }

                // 对照：旧写法的线程（应当与上面不同）
                CoroutineScope(SupervisorJob() + Dispatchers.Swing).launch {
                    record("[swing-old] thread=${Thread.currentThread().name}")
                }

                delay(3_000)
                record("=== all threads ===")
                Thread.getAllStackTraces().keys.map { it.name }.sorted().forEach { record("  $it") }
                record("=== done ===")
                exitProcess(0)
            }
            Text("probe")
        }
    }
}