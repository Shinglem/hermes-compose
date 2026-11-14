package dev.shinglem.hermes.shell.spike

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.tao.TaoMonitors
import java.io.File
import kotlin.system.exitProcess

/**
 * 探针：Nucleus 在 **Windows** 上能不能给出显示器几何。
 *
 * 若可用，`NotificationOverlay` 就不用再依赖 AWT 的 `GraphicsEnvironment`
 * （那是 ADR-067 保留的最后一个窗口层 AWT 依赖）。
 *
 * `NativeTaoWindowsDecoBridge` 是 internal，所以走公开路径：
 * `NucleusWindow.unsafe.taoWindow` → `TaoMonitors.all(...)`。
 */
fun main() {
    val out = File("build/monitor-probe.txt")
    out.parentFile?.mkdirs()
    val lines = mutableListOf<String>()

    fun record(text: String) {
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    record("=== monitor geometry probe ===")

    nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
        DecoratedWindow(
            onCloseRequest = {},
            state = rememberWindowState(),
            title = "Monitor Probe",
        ) {
            LaunchedEffect(Unit) {
                val taoWindow = nucleusWindow.unsafe.taoWindow
                record("[tao] window=${taoWindow}")

                record(
                    "[tao] monitors=" + runCatching {
                        TaoMonitors.all(taoWindow).joinToString("; ") {
                            "id=${it.id} name=${it.name} bounds=${it.boundsPx} work=${it.workAreaPx} " +
                                "scale=${it.scaleFactor} primary=${it.isPrimary}"
                        }
                    }.getOrElse { "THROWS: ${it::class.java.name}: ${it.message}" },
                )
                record(
                    "[tao] primary=" + runCatching {
                        val p = TaoMonitors.primary(taoWindow)
                        "work=${p.workAreaPx} scale=${p.scaleFactor}"
                    }.getOrElse { "THROWS: $it" },
                )
                record(
                    "[tao] forWindow=" + runCatching {
                        val m = TaoMonitors.forWindow(taoWindow)
                        "work=${m.workAreaPx}"
                    }.getOrElse { "THROWS: $it" },
                )

                // 对照：AWT 现在给出的值
                record(
                    "[awt] default bounds=" + runCatching {
                        val d = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                        "${d.defaultConfiguration.bounds} scale=${d.defaultConfiguration.defaultTransform.scaleX}"
                    }.getOrElse { "THROWS: $it" },
                )
                record("=== done ===")
                exitProcess(0)
            }
            Text("monitor probe")
        }
    }
}