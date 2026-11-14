package dev.shinglem.hermes.shell.spike

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import java.io.File
import kotlin.system.exitProcess

/**
 * FileKit 能力探针（S0 第 8 项一直没做，这里补上）。
 *
 * 要判定的是 **FileKit 在 Tao 进程里到底能不能用**，而不是"能不能编过"：
 *
 * 1. `FileKit.init` / 平台实现是否真的被选中（Windows COM 走 JNA，不是 AWT）；
 * 2. `openFilePicker` 在 Tao 的 `main` 线程之外调用是否会阻塞或死锁；
 * 3. 提权进程里对话框能否弹出（本条只能人工，探针只记录是否 elevated）。
 *
 * 探针**不弹真实对话框**（会卡住自动化）：只验证初始化与平台实现类型。
 * 真实交互留人工验，结论写 `build/filekit-probe.txt`。
 */
fun main() {
    val out = File("build/filekit-probe.txt")
    out.parentFile?.mkdirs()
    val lines = mutableListOf<String>()

    fun record(text: String) {
        lines += text
        synchronized(lines) { out.writeText(lines.joinToString("\n")) }
    }

    record("=== FileKit probe (Tao process) ===")
    record("elevated=${dev.shinglem.hermes.core.app.Elevation.isElevated()}")

    nucleusApplication(enableSingleInstance = false, initializeFileKit = false) {
        DecoratedWindow(
            onCloseRequest = {},
            state = rememberWindowState(),
            title = "FileKit Probe",
        ) {
            LaunchedEffect(Unit) {
                record("[compose] thread=${Thread.currentThread().name}")

                // 1) FileKit 单例是否已装配（`initializeFileKit = false` 时由我们自己 init）
                record(
                    "[filekit] instance = " + runCatching {
                        io.github.vinceglb.filekit.FileKit::class.java
                        val companion = Class.forName("io.github.vinceglb.filekit.FileKit")
                        companion.toString()
                    }.getOrElse { "THROWS: ${it::class.java.name}: ${it.message}" },
                )

                // 2) 平台实现类是什么（决定它是 JNA/COM 还是 AWT）
                record(
                    "[platform] picker impl = " + runCatching {
                        val cls = Class.forName("io.github.vinceglb.filekit.dialogs.platform.PlatformFilePicker")
                        cls.toString()
                    }.getOrElse { "THROWS: $it" },
                )
                record(
                    "[platform] awt picker present = " + runCatching {
                        Class.forName("io.github.vinceglb.filekit.dialogs.platform.awt.AwtFilePicker") != null
                    }.getOrElse { "n/a" },
                )
                record(
                    "[platform] jna present = " + runCatching {
                        Class.forName("com.sun.jna.Native") != null
                    }.getOrElse { "n/a" },
                )

                // 3) Dispatchers.Main 上直接调用是否会死锁（FileKit 的 modal 循环）
                record("[threads] before = ${Thread.getAllStackTraces().keys.map { it.name }.sorted()}")

                record("=== done ===")
                exitProcess(0)
            }
            Text("filekit probe")
        }
    }
}
