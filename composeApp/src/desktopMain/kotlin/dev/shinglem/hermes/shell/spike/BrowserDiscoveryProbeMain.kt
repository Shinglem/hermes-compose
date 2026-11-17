package dev.shinglem.hermes.shell.spike

import dev.shinglem.hermes.core.browser.BrowserDiscovery
import dev.shinglem.hermes.core.browser.DevToolsPortFile
import dev.shinglem.hermes.core.browser.Win32WindowQuery
import java.io.File

/**
 * 探针：`BrowserDiscovery` 能否在**真实后端 launcher 拉起的浏览器**上走到 `Found`。
 *
 * 与 `BrowserAttachProbeMain` 的分工：那个验"拿到 HWND 之后能不能嵌"（视觉），
 * 这个验"能不能拿到 HWND"（纯逻辑，无窗口）。
 *
 * ### 怎么造被测对象
 *
 * 必须用**后端自己的 launcher**（`launch_chromium`），不是 `Start-Process chrome.exe`：
 * 只有它写的 launch record 与 `DevToolsActivePort` 才代表 agent 的真实形态。
 *
 * ```powershell
 * $py = 'release-test\hermes\tools\python-3.14.9+20261021-win32-x64\python.exe'
 * $env:PYTHONPATH = (Resolve-Path 'release-test\hermes\venv\Lib\site-packages').Path
 * # 让 launcher 拉起后常驻，记下 HERMES_HOME 与 pid
 * .\gradlew.bat :composeApp:browserDiscoveryProbe -Phermes.probe.home=<HERMES_HOME>
 * ```
 *
 * 结果写 `build/browser-discovery-probe.txt`。
 */
fun main() {
    val out = File("build/browser-discovery-probe.txt")
    out.parentFile?.mkdirs()
    out.writeText("")

    val lines = mutableListOf<String>()
    val record: (String) -> Unit = { text ->
        synchronized(lines) {
            lines += text
            out.writeText(lines.joinToString("\n"))
        }
    }

    val home = System.getProperty("hermes.probe.home")
    record("=== browser discovery probe ===")
    record("[env] supported=${Win32WindowQuery.supported} home=$home")

    if (home.isNullOrBlank()) {
        record("[fatal] 没给 -Phermes.probe.home")
        return
    }

    val discovery = BrowserDiscovery(hermesHome = File(home))
    record("[info] recordsPath=${discovery.recordsPath}")

    val outcome = discovery.discover()
    when (outcome) {
        is dev.shinglem.hermes.core.browser.DiscoveryOutcome.Found -> {
            record("[Found] ${outcome.targets.size} 只")
            outcome.targets.forEach { t ->
                record("  pid=${t.pid} hwnd=${t.hwnd} port=${t.port} ephemeral=${t.ephemeral}")
                record("  userDataDir=${t.userDataDir}")
                record("  isWindow=${Win32WindowQuery.isWindow(t.hwnd)} portFile=${DevToolsPortFile.read(t.userDataDir)}")
            }
        }
        is dev.shinglem.hermes.core.browser.DiscoveryOutcome.Headless ->
            record("[Headless] pid=${outcome.pid}（进程在但没有可见窗口）")
        dev.shinglem.hermes.core.browser.DiscoveryOutcome.NoRecord ->
            record("[NoRecord] 没有活着的浏览器记录")
        dev.shinglem.hermes.core.browser.DiscoveryOutcome.Unsupported ->
            record("[Unsupported] 非 Windows")
    }
    record("=== done ===")
}
