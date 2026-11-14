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
 * 探针：`TaoMonitors.all()` **不需要** `TaoWindow` 也能拿到显示器信息？
 *
 * 若成立，`Main` 里那段"首帧 AWT 兜底"就是多余依赖 —— 那是最后一个还能去掉的 AWT 窗口层调用。
 * 读字节码看到 Windows 分支根本不读 `taoWindow` 参数、`all$default` 的默认值是 null，
 * 这里实测确认。
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

    record("=== TaoMonitors without TaoWindow probe ===")
    // 一、完全不在 nucleusApplication 里（最早期）
    record(
        "[early] all(null)=" + runCatching {
            TaoMonitors.all(null).joinToString("; ") { "work=${it.workAreaPx} scale=${it.scaleFactor}" }
        }.getOrElse { "THROWS: ${it::class.java.name}: ${it.message}" },
    )
    record(
        "[early] primary(null)=" + runCatching {
            TaoMonitors.primary(null).let { "work=${it.workAreaPx} scale=${it.scaleFactor}" }
        }.getOrElse { "THROWS: $it" },
    )

    nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
        DecoratedWindow(
            onCloseRequest = {},
            state = rememberWindowState(),
            title = "Monitor Probe",
        ) {
            LaunchedEffect(Unit) {
                record(
                    "[inside] all(null)=" + runCatching {
                        TaoMonitors.all(null).joinToString("; ") { "work=${it.workAreaPx}" }
                    }.getOrElse { "THROWS: $it" },
                )
                record(
                    "[inside] all(tao)=" + runCatching {
                        TaoMonitors.all(nucleusWindow.unsafe.taoWindow).joinToString("; ") {
                            "work=${it.workAreaPx}"
                        }
                    }.getOrElse { "THROWS: $it" },
                )
                record(
                    "[inside] synthetic-fallback=" + runCatching {
                        // 没有窗口时的合成显示器（依赖 primaryMonitorWorkAreaPx，需要窗口）
                        TaoMonitors.primary(null).workAreaPx
                    }.getOrElse { "THROWS: $it" },
                )
                record("=== done ===")
                exitProcess(0)
            }
            Text("probe")
        }
    }
}