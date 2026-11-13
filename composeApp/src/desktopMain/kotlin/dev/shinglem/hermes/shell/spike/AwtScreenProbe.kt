package dev.shinglem.hermes.shell.spike

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Toolkit
import java.io.File

/**
 * S6 前置探针：Tao 进程里 AWT 的**屏幕几何** API 还能不能用。
 *
 * 背景：`ui/notification/NotificationOverlay.kt` 靠 `GraphicsEnvironment` /
 * `Toolkit.getScreenInsets` / `MouseInfo` 算通知窗位置（贴右下角、多屏取鼠标所在屏、DPI 换算）。
 * S0 的 spike 只验了剪贴板，**没验这些**。如果 Tao 下它们抛 `HeadlessException` 或返回假数据，
 * 通知浮窗就得换成 Tao 自己的屏幕 API。
 *
 * **独立进程 + 写文件**，不依赖 Compose 渲染：上一版把它放在 `remember { }` 里，
 * 结果窗口白屏且进程不退出 —— 那本身就是「这些调用可能阻塞 UI 线程」的信号。
 *
 * 跑法：`:composeApp:taoAwtProbe`（见 build.gradle.kts），结果落在
 * `%TEMP%/hermes-awt-probe.txt`，同时打 stdout。
 */
object AwtScreenProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(System.getProperty("java.io.tmpdir"), "hermes-awt-probe.txt")
        val lines = mutableListOf<String>()

        fun step(name: String, block: () -> String) {
            val t0 = System.nanoTime()
            val result = runCatching(block).fold(
                onSuccess = { it },
                onFailure = { "THREW ${it::class.simpleName}: ${it.message}" },
            )
            val ms = (System.nanoTime() - t0) / 1_000_000
            lines += "$name = $result  (${ms}ms)"
        }

        step("isHeadless") { GraphicsEnvironment.isHeadless().toString() }
        step("getLocalGraphicsEnvironment") {
            GraphicsEnvironment.getLocalGraphicsEnvironment()::class.java.name
        }
        step("screenDevices.size") {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size.toString()
        }
        step("defaultScreenDevice") {
            val d = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val b = d.defaultConfiguration.bounds
            "${b.width}x${b.height}@${b.x},${b.y}"
        }
        step("screenInsets") {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(env.defaultScreenDevice.defaultConfiguration)
            "${insets.left},${insets.top},${insets.right},${insets.bottom}"
        }
        step("defaultTransform.scaleX") {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            env.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toString()
        }
        step("MouseInfo.pointerInfo") {
            val p = MouseInfo.getPointerInfo()
            if (p == null) "null" else "${p.location.x},${p.location.y}"
        }

        out.writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n"))
    }
}
