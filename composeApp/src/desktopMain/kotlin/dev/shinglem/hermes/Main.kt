package dev.shinglem.hermes

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.app.DesktopTray
import dev.shinglem.hermes.ui.notification.NotificationOverlay
import dev.shinglem.hermes.core.app.Elevation
import dev.shinglem.hermes.core.app.ElevationRelaunch
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.app.RestartHandover
import dev.shinglem.hermes.core.app.ScheduledAutostart
import dev.shinglem.hermes.core.app.SingleInstance
import dev.shinglem.hermes.core.app.TrayMenuItem
import dev.shinglem.hermes.core.backend.BackendBootState
import dev.shinglem.hermes.core.config.AutostartMode
import dev.shinglem.hermes.core.config.ConfigEnvNames
import dev.shinglem.hermes.core.config.ConfigError
import dev.shinglem.hermes.core.config.ConfigPaths
import dev.shinglem.hermes.core.config.ElevateMode
import dev.shinglem.hermes.core.config.SystemConfigSource
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.log.StartupLog
import dev.shinglem.hermes.core.prefs.WindowBounds
import dev.shinglem.hermes.di.DesktopContainer
import dev.shinglem.hermes.feature.terminal.TerminalContent
import dev.shinglem.hermes.platform.TerminalQuoteBridge
import dev.shinglem.hermes.platform.NativeNotifications
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.icon
import dev.shinglem.hermes.terminal.terminalSelectedText
import dev.shinglem.hermes.terminal.terminalTailText
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import java.net.URI
import kotlinx.coroutines.launch
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.painterResource

/**
 * 启动参数：
 * - `--silent` / `--tray`：不显示主窗口，直接驻留托盘（开机自启用这个）。
 * - 其余参数忽略（jpackage 启动器会塞 `jpackage.app-path` 之类）。
 */
private fun isSilent(args: Array<String>): Boolean =
    args.any { it.equals("--silent", ignoreCase = true) || it.equals("--tray", ignoreCase = true) }

/**
 * 公式引擎能否初始化（写进 startup.log 的一行状态）。
 *
 * ratex 的 native dll 走「先删后拷」解压，目标被占用时只会静默退化 —— 没有这一行，
 * 「公式显示成源码」在日志里完全看不出来。
 */
private fun ratexStatus(): String =
    runCatching { Class.forName("io.ratex.RaTeXEngine") }
        .fold(
            onSuccess = { "ratex=ok" },
            onFailure = { error -> "ratex=FAILED ${error.cause?.message ?: error.message}" },
        )

/**
 * `<home>` 的早期定位：只依赖 `-Dhdc.home` / `HDC_HOME` / exe 位置，**不读配置文件**。
 * 与 [dev.shinglem.hermes.core.config.ConfigPaths.resolveHome] 同一套规则，这里再取一次是为了
 * 在配置解析之前就能建 [StartupLog]。
 */
private fun startupHome(): File = runCatching {
    ConfigPaths.resolveHome(SystemConfigSource)
}.getOrElse { File(System.getProperty("user.dir") ?: ".") }

/**
 * 已有一份在跑：唤醒它然后退出。
 *
 * `--silent` 的第二次触发（计划任务）**不唤醒**：用户正在用那一份，别把窗口抢到前台。
 * 唤醒送不到时（跨权限被拒、对方是旧版本）必须给反馈 —— 静默退出等于"点了没反应"。
 */
private fun exitAsSecondInstance(startup: StartupLog, silent: Boolean, home: File): Nothing {
    if (silent) {
        startup.info("instance", "already running; silent trigger exits without activating")
        exitProcess(EXIT_ALREADY_RUNNING)
    }
    val activated = SingleInstance.requestActivate(home)
    startup.info("instance", "already running; activate=$activated")
    if (!activated) {
        showFatal(
            "${ProductInfo.DISPLAY_NAME} 已在运行",
            "已有一份 ${ProductInfo.DISPLAY_NAME} 在运行（可能在系统托盘里）。\n如果看不到窗口，请从托盘图标打开。",
            silent = false,
        )
    }
    exitProcess(EXIT_ALREADY_RUNNING)
}

/**
 * 计划任务要启动的是**启动器 exe**（`hermes-desktop-compose.exe`），不是 `javaw.exe` ——
 * 后者需要完整 JVM 参数，而参数只在 `app\hermes-desktop-compose.cfg` 里。
 *
 * `jpackage.app-path` 由启动器自己注入（`06` 篇 §7.6 实测）；从开发用的 `App.bat`
 * 或 `gradlew run` 起时退回 `<home>/hermes-desktop-compose.exe`。两者都没有（开发态）就返回 null，自启动保持关闭。
 */
private fun launcherExe(home: File): File? {
    System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { path ->
        val file = File(path)
        if (file.isFile) return file
    }
    return File(home, "$APP_NAME.exe").takeIf { it.isFile }
}

/** 与 `composeApp/build.gradle.kts` 里的 `appName` 保持一致（jpackage 产物文件名）。 */
private const val APP_NAME = "hermes-desktop-compose"

/** 第二个实例：不算错误，但退出码要能与"正常退出"区分（便于脚本判断）。 */
private const val EXIT_ALREADY_RUNNING = 0


fun main(args: Array<String>) {
    // BossTerm 的默认存储是 `~/.bossterm`（settings / 字体缓存 / 会话快照）。统一改到我们的数据目录，
    // 别在用户家目录留下第二个配置文件夹；终端设置本身由 state.json 注入，不走这个文件。
    System.setProperty("bossterm.settings.dir", File(appDataDir, "terminal").absolutePath)
    // 托盘菜单是 Swing 原生菜单：跟着系统外观走才与截图里的系统菜单一致（Compose 自绘的部分
    // 不受 LAF 影响）。必须在创建任何 Swing 组件之前设置。
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val silent = isSilent(args)
    // 极早期日志：配置解析失败也要留痕（`--silent` 下 showFatal 只写 stderr，javaw 没有控制台）。
    val startup = StartupLog(startupHome())
    startup.info("start", "pid=${ProcessHandle.current().pid()} elevated=${Elevation.isElevated()} silent=$silent")
    startup.info("args", args.joinToString(" ").ifBlank { "(none)" })
    startup.info("cwd", System.getProperty("user.dir").orEmpty())
    startup.info("home", startup.homePath)
    // 公式引擎的 native dll 由 ratex 自己解压到 `%LOCALAPPDATA%\RaTeX\…`，解压是「先删后拷」：
    // 目标被别的进程占用时整条公式渲染会**静默**退化成源码。这里提前触发一次并把结果写进日志，
    // 否则「公式显示成源码」只能靠猜（实测踩过：上一个实例没退干净，新实例全篇公式都是源码）。
    startup.info("formula", ratexStatus())

    // 配置解析在 application{} 之外：失败时只该弹一个原生对话框然后退出，
    // 不要先起一个空窗口再报错。
    val container = try {
        DesktopContainer.build()
    } catch (failure: ConfigError) {
        startup.error("config", failure.message.orEmpty())
        showFatal("配置错误", failure.message.orEmpty(), silent)
        exitProcess(EXIT_CONFIG_ERROR)
    } catch (failure: Throwable) {
        startup.error("startup", failure.message ?: failure::class.java.name)
        showFatal("启动失败", failure.message ?: failure::class.java.name, silent)
        exitProcess(EXIT_STARTUP_ERROR)
    }
    startup.info("config", "ok mode=${container.config.backend.mode} log=${container.config.app.logLevel}")

    // 原生通知（Windows toast）的实例身份按 <home> 哈希，与单实例命名同源：
    // 两份便携包各有独立的 AUMID 与开始菜单快捷方式，互不覆盖。
    NativeNotifications.attachHome(File(startup.homePath))
    NativeNotifications.sync(container.preferences.nativeNotifications)

    // 单实例判定：必须在"提权重启"之前 —— 否则用户先被 UAC 拦一道，点完"是"才被告知已有一份。
    // 正式占位则在提权之后 —— 互斥量一旦创建，提权后的新进程会把正在退出的旧进程当成已有实例 → 活锁。
    // 完整顺序见 .design/详细设计/工程与代码/08-单实例与启动时序.md §5。
    val home = startupHome()

    // 「重启客户端」拉起的新进程此刻被旧进程的互斥量挡着，先等它把锁交出来（见 RestartHandover）。
    RestartHandover.parse(args.toList())?.let { predecessor ->
        startup.info("instance", "handover: waiting for predecessor pid=$predecessor")
        if (!RestartHandover.awaitExit(predecessor)) {
            startup.warn("instance", "handover: predecessor pid=$predecessor still alive")
        }
    }

    if (SingleInstance.isRunning(home)) {
        exitAsSecondInstance(startup, silent, home)
    }

    // 提权：此刻**尚未持有**互斥量，可以安全地 runas 重启自己。
    // 交接参数要剥掉：新旧进程之间已经交过一次，提权后的进程不该再等那个（已死的）PID，
    // 万一 PID 被复用还会白等一轮。
    if (container.config.app.elevate == ElevateMode.ASK && !Elevation.isElevated()) {
        val relaunchArgs = RestartHandover.strip(args.toList()).toTypedArray()
        when (ElevationRelaunch.relaunch(relaunchArgs, System.getProperty("user.dir"))) {
            ElevationRelaunch.Outcome.STARTED -> {
                startup.info("elevate", "relaunched via runas; current process exits")
                exitProcess(0)
            }

            ElevationRelaunch.Outcome.CANCELLED -> {
                // 用户点了"否"：不是错误，继续以普通权限跑（配置仍是 ask，下次启动还会问）。
                startup.warn("elevate", "UAC declined by user; continuing without elevation")
            }

            ElevationRelaunch.Outcome.FAILED -> {
                startup.error("elevate", "runas failed; continuing without elevation")
            }
        }
    }

    val instance = SingleInstance.acquire(home)
        ?: // 探测与占位之间被别的进程抢先了（两个客户端同时启动）。
        exitAsSecondInstance(startup, silent, home)
    startup.info("instance", "acquired home=${home.absolutePath}")

    // 开机自启：写计划任务需要管理员，所以放在提权之后再对账（07 篇 §3.6）。
    val autostartStatus = ScheduledAutostart.reconcile(
        mode = container.config.app.autostart,
        home = home,
        exe = launcherExe(home),
        elevated = container.elevated,
    )
    container.autostart.value = AutostartInfo(
        configured = container.config.app.autostart == AutostartMode.TASK,
        taskName = autostartStatus.taskName,
        installed = autostartStatus.installed,
        pending = autostartStatus.pending,
        elevated = container.elevated,
    )
    startup.info(
        "autostart",
        "task=${autostartStatus.taskName} installed=${autostartStatus.installed} " +
            "exeArgsMatch=${autostartStatus.matchesExeAndArgs} pending=${autostartStatus.pending} " +
            "configured=${container.config.app.autostart}",
    )
    // 便携包挪过位置会留下指向旧路径的任务：只读扫描，交给设置页逐条确认删除。
    val orphans = ScheduledAutostart.scanOrphans(autostartStatus.taskName)
    container.orphanTasks.value = orphans
    if (orphans.isNotEmpty()) {
        startup.warn(
            "autostart",
            "发现 ${orphans.size} 个失效的自启任务：" + orphans.joinToString("; ") { "${it.name} -> ${it.command}" },
        )
    }

    val activateSignal = MutableStateFlow(0)
    instance.onActivate {
        // 唤醒线程在后台跑：只改信号，切前台的动作交给 Compose 侧（见下面的 LaunchedEffect）。
        activateSignal.value += 1
    }

    val windowConfig = container.config.app.window
    val closeToTray = container.config.app.closeToTray
    val savedBounds = container.preferences.windowBounds

    application {
        val windowState = rememberWindowState(
            width = (savedBounds?.width ?: windowConfig.width).dp,
            height = (savedBounds?.height ?: windowConfig.height).dp,
            position = savedBounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        )
        // 后端必须与窗口是否可见无关：静默启动（--silent）时窗口不进入组合，
        // 若把 start() 放在 App() 里，托盘模式就永远没有后端。
        LaunchedEffect(Unit) { container.backend.start() }

        // 托盘常驻：关窗只是隐藏，只有托盘菜单的「退出」才结束进程与后端子进程。
        var windowVisible by remember { mutableStateOf(!silent) }
        val boot = container.backend.state
        // 窗口 / 任务栏 / 托盘共用一个图标资源（源是 packaging/icon.svg）。不设的话
        // 任务栏显示的是 Java 咖啡杯 —— 与 exe 的图标对不上。
        val appIcon = painterResource(Res.drawable.icon)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        // 只算一次：`painterResource` 每次重组都可能给出新的 Painter 实例，
        // 用它当 remember key 会反复重建托盘图标（图标闪、甚至短暂出现两个）。
        val trayIconImage = remember { appIcon.toAwtImage(density, layoutDirection, Size(16f, 16f)) }

        // 第二个实例发来的"显示窗口"请求：从托盘里拉出来，并尽力前置。
        val activation by activateSignal.collectAsState()
        LaunchedEffect(activation) {
            if (activation > 0) {
                windowVisible = true
                startup.info("activate", "request #$activation -> window shown")
            }
        }

        fun quit() {
            startup.info("exit", "requested from tray")
            instance.close()
            container.close()
            exitApplication()
        }

        // 通知窗：与主窗口平级（`application` 作用域），托盘模式下主窗口不在组合里也能弹。
        NotificationOverlay()

        val tray = remember(trayIconImage) {
            DesktopTray(
                icon = trayIconImage,
                tooltip = ProductInfo.DISPLAY_NAME,
                onActivate = { windowVisible = true },
                menu = {
                    val dashboardUrl = (boot.value as? BackendBootState.Ready)?.dashboardUrl
                    listOf(
                        TrayMenuItem.Action("显示主窗口") { windowVisible = true },
                        TrayMenuItem.Action(
                            text = "打开 Web 界面",
                            enabled = dashboardUrl != null,
                        ) {
                            (boot.value as? BackendBootState.Ready)?.dashboardUrl?.let(::openInBrowser)
                        },
                        TrayMenuItem.Separator,
                        TrayMenuItem.Action("退出") { quit() },
                    )
                },
            )
        }
        DisposableEffect(tray) {
            tray.install()
            onDispose { tray.dispose() }
        }

        if (windowVisible) {
            Window(
                onCloseRequest = {
                    // 最大化时记的是**还原后**的 bounds：下次启动先按普通窗口恢复。
                    if (windowState.placement == WindowPlacement.Floating) {
                        container.preferences.windowBounds = windowState.toBounds()
                    }
                    container.preferences.flush()
                    if (closeToTray) windowVisible = false else quit()
                },
                title = ProductInfo.DISPLAY_NAME,
                icon = appIcon,
                state = windowState,
            ) {
                LaunchedEffect(Unit) {
                    window.minimumSize = Dimension(windowConfig.minWidth, windowConfig.minHeight)
                }
                App(
                    container = container,
                    onExitRequest = { quit() },
                    terminalContent = { _, modifier ->
                        TerminalContent(
                            viewModel = container.terminalViewModel,
                            modifier = modifier,
                        )
                    },
                    onQuoteTerminalSelection = {
                        val tabId = container.terminalViewModel.state.value.activeTab?.id
                        val selected = tabId?.let { terminalSelectedText(it) }
                        if (!selected.isNullOrBlank()) {
                            TerminalQuoteBridge.publish(selected)
                        } else if (tabId != null) {
                            // 没有选中就退一步：取最近 40 行（"把刚才的输出带进对话"是最常见诉求）。
                            TerminalQuoteBridge.publish(terminalTailText(tabId, 40))
                        }
                    },
                )
            }
        }
    }
}

/** 位置还没定下来（`PlatformDefault` 且窗口未上屏）时不记，免得把 0,0 写进 state.json。 */
private fun WindowState.toBounds(): WindowBounds? {
    val position = position
    if (!position.isSpecified) return null
    return WindowBounds(
        x = position.x.value.toInt(),
        y = position.y.value.toInt(),
        width = size.width.value.toInt(),
        height = size.height.value.toInt(),
    )
}

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/** 配置/装配阶段既没有日志、也没有窗口，只能用 Swing 的原生对话框。 */
private fun showFatal(title: String, message: String, silent: Boolean) {
    // 先落 stderr 再弹框：对话框是阻塞的，反过来会让自动化测试永远读不到原因。
    System.err.println("[$title] $message")
    // 静默启动（开机自启）时不该弹模态框卡住会话。
    if (silent || System.getenv(ConfigEnvNames.NO_GUI_DIALOG) == "1") return
    runCatching {
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
    }
}

/** 配置错误与启动期错误的退出码要能区分（便于脚本判断失败原因）。 */
private const val EXIT_CONFIG_ERROR = 2
private const val EXIT_STARTUP_ERROR = 3
