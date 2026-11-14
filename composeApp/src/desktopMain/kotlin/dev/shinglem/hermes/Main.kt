package dev.shinglem.hermes

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.SingleInstanceManager
import dev.nucleusframework.window.TitleBar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import dev.shinglem.hermes.core.app.AutostartInfo
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.shell.window.rememberHermesTitleBarStyle
import dev.shinglem.hermes.ui.notification.NotificationOverlay
import dev.shinglem.hermes.ui.notification.WorkArea
import dev.shinglem.hermes.ui.theme.HermesTheme
import dev.shinglem.hermes.core.app.NativeMessageBox
import dev.shinglem.hermes.core.app.Elevation
import dev.shinglem.hermes.core.app.ElevationRelaunch
import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.app.RestartHandover
import dev.shinglem.hermes.core.app.ScheduledAutostart
import dev.shinglem.hermes.shell.entry.SingleInstanceConfig
import dev.shinglem.hermes.shell.tray.NucleusTray
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
import dev.shinglem.hermes.di.KoinStartup
import dev.shinglem.hermes.feature.terminal.TerminalContent
import dev.shinglem.hermes.platform.AppFocus
import dev.shinglem.hermes.platform.SystemNotifications
import dev.shinglem.hermes.platform.TerminalQuoteBridge
import dev.shinglem.hermes.platform.NativeNotifications
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.icon
import dev.shinglem.hermes.terminal.terminalSelectedText
import dev.shinglem.hermes.terminal.terminalTailText
import java.io.File
import kotlinx.coroutines.launch
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

fun main(args: Array<String>) {
    // BossTerm 的默认存储是 `~/.bossterm`（settings / 字体缓存 / 会话快照）。统一改到我们的数据目录，
    // 别在用户家目录留下第二个配置文件夹；终端设置本身由 state.json 注入，不走这个文件。
    System.setProperty("bossterm.settings.dir", File(appDataDir, "terminal").absolutePath)
    // LAF 与 `JOptionPane` 都已移除（ADR-075）：托盘菜单换成 `composenativetray` 的原生菜单，
    // 致命错误弹窗换成 Win32 `MessageBoxW`。业务代码不再引用 `java.desktop`。
    val silent = isSilent(args)
    // 极早期日志：配置解析失败也要留痕（`--silent` 下 showFatal 只写 stderr，javaw 没有控制台）。
    val startup = StartupLog(startupHome())
    startup.info("start", "pid=${ProcessHandle.current().pid()} elevated=${Elevation.isElevated()} silent=$silent")
    startup.info("args", args.joinToString(" ").ifBlank { "(none)" })
    startup.info("cwd", System.getProperty("user.dir").orEmpty())
    startup.info("home", startup.homePath)

    // ── 单实例配置：必须在第一次 `isSingleInstance()` 之前设好 ────────────────────
    // 只设 `configuration`，**判定本身交给 Nucleus**（`nucleusApplication` 里的
    // `acquireSingleInstanceLock`）。不要自己提前判定：首实例拿到锁时 `onLockAcquired`
    // 会用 `onRestoreRequest` 去 `watchForRestoreRequests()`，那是**唯一**能让
    // "第二实例请求 → 拉出窗口"生效的地方，而配套的 `SingleInstanceRestoreBus` 是 internal。
    // 曾试过"配置解析前探测锁、判为第二实例就提前退出"，绕过了这条路径 → 已回退（ADR-066）。
    val home = startupHome()
    when (val lock = SingleInstanceConfig.prepare(home)) {
        is SingleInstanceConfig.Outcome.Ready -> {
            // 不设的话 Nucleus 用默认配置 —— 锁文件落在 `java.io.tmpdir` 下、名字按主类派生
            // （实测 `dev.shinglem.hermes.MainKt.lock`），提权/非提权会话的 TEMP 不同就会互相
            // 看不见（这正是 `LockDir` 要自定义目录的原因）。
            SingleInstanceManager.configuration = lock.configuration
            startup.info(
                "instance",
                "lock=${lock.configuration.lockFilePath} id=${lock.configuration.lockIdentifier}",
            )
        }

        is SingleInstanceConfig.Outcome.Unavailable -> {
            // Nucleus 在拿不到锁时会 fail-open 返回 true（允许双开）。明确记错误但不阻断。
            startup.error("instance", "单实例不可用：${lock.reason}（可能允许双开）")
        }
    }

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

    // Koin 图（ADR-076）：S5 的 Component 树按类型取依赖。
    // 必须在 `AppShell` 组合之前起来 —— 那里的 `AppRoot` 会 `KoinApplication {}`。
    KoinStartup.start(container)

    // 原生通知（Windows toast）的实例身份按 <home> 哈希，与单实例命名同源：
    // 两份便携包各有独立的 AUMID 与开始菜单快捷方式，互不覆盖。
    NativeNotifications.attachHome(File(startup.homePath))
    NativeNotifications.sync(container.preferences.nativeNotifications)

    // 「重启客户端」拉起的新进程此刻被旧进程的锁挡着，先等它把锁交出来（见 RestartHandover）。
    RestartHandover.parse(args.toList())?.let { predecessor ->
        startup.info("instance", "handover: waiting for predecessor pid=$predecessor")
        if (!RestartHandover.awaitExit(predecessor)) {
            startup.warn("instance", "handover: predecessor pid=$predecessor still alive")
        }
    }

    // 提权：第二实例已在上面退出，走到这里的必然是首实例。
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

    // 单实例锁由 `nucleusApplication` 内部的 `acquireSingleInstanceLock` 取得（上面只设了配置）。
    startup.info("instance", "home=${home.absolutePath}（单实例由 Nucleus 判定）")
    // 公式引擎的 native dll 由 ratex 自己解压到 `%LOCALAPPDATA%\RaTeX\…`，解压是「先删后拷」：
    // 目标被别的进程占用时整条公式渲染会**静默**退化成源码。这里提前触发一次并把结果写进日志，
    // 否则「公式显示成源码」只能靠猜（实测踩过：上一个实例没退干净，新实例全篇公式都是源码）。
    // 必须放在单实例获取**之后**：第二个实例只是去唤醒已有窗口，不该去碰那个 dll。
    startup.info("formula", ratexStatus())

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

    val windowConfig = container.config.app.window
    val closeToTray = container.config.app.closeToTray
    val savedBounds = container.preferences.windowBounds

    // Tao 窗口壳（ADR-062）。`enableSingleInstance = true` 让 Nucleus 判定单实例并负责
    // 「第二实例把窗口拉出」（ADR-066）；`--silent` 首启不显示由 `visible` 控制。
    nucleusApplication(
        args = args,
        enableSingleInstance = true,
        initializeFileKit = false,
    ) {
        val windowState = rememberWindowState(
            width = (savedBounds?.width ?: windowConfig.width).dp,
            height = (savedBounds?.height ?: windowConfig.height).dp,
            position = savedBounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        )
        // 后端必须与窗口是否可见无关：静默启动（--silent）时窗口不显示，
        // 若把 start() 放在 App() 里，托盘模式就永远没有后端。
        LaunchedEffect(Unit) { container.backend.start() }

        // 托盘常驻：关窗只是隐藏，只有托盘菜单的「退出」才结束进程与后端子进程。
        var windowVisible by remember { mutableStateOf(!silent) }
        val boot = container.backend.state
        // 窗口 / 任务栏 / 托盘共用一个图标资源（源是 packaging/icon.svg）。
        val appIcon = painterResource(Res.drawable.icon)

        fun quit() {
            startup.info("exit", "requested from tray")
            // 先停图再拆容器：Component 的 onDestroy 可能还要从图里取东西。
            KoinStartup.stop()
            container.close()
            exitApplication()
        }

        // 通知窗：与主窗口平级（`nucleusApplication` 作用域），托盘模式下主窗口不显示也能弹。
        //
        // 几何直接问 Tao 的原生显示器信息（`TaoMonitors` 不需要窗口句柄 —— 见
        // `taoWorkAreaOrNull` 的注释）。早期实现在这里靠主窗口回写 + AWT 兜底，两者都不必要。
        val workArea = remember { mutableStateOf(taoWorkAreaOrNull()) }
        NotificationOverlay(workArea = { workArea.value ?: WorkArea(0f, 0f, 1920f, 1080f) })

        // 托盘：`composenativetray` 的原生桥（解决 CMP-1936 中文菜单乱码）。
        // 点图标先 `consumePendingAction()` 再决定显示/退出（沿用旧顺序）。
        //
        // 这里顺带把"点通知时把窗口拉出来"的兜底注册给 `SystemNotifications`：
        // 它以前由已删除的 `DesktopTray.install()` 注入，而那个类在 S6 换 NucleusTray 后
        // 就再没被实例化过 —— 纯提示通知因此一直静默丢掉（见 `Notifications.desktop.kt` 注释）。
        NucleusTray(
            icon = appIcon,
            tooltip = ProductInfo.DISPLAY_NAME,
            onActivate = {
                SystemNotifications.consumePendingAction()
                windowVisible = true
            },
            onShow = { windowVisible = true },
            onQuit = { quit() },
        )
        LaunchedEffect(Unit) {
            SystemNotifications.attachActivation { windowVisible = true }
        }

        // 窗口**常驻组合、只切换可见性**（`visible`），不用 `if (windowVisible)` 整块移除。
        // 理由见 `08-单实例与启动时序.md`：销毁再重建窗口会让输入法的 `InputMethodRequests` 为空，
        // 用户此时敲下的第一个键会绕过输入法直接落进输入框。
        DecoratedWindow(
            onCloseRequest = {
                // 最大化时记的是**还原后**的 bounds：下次启动先按普通窗口恢复。
                if (windowState.placement == WindowPlacement.Floating) {
                    container.preferences.windowBounds = windowState.toBounds()
                }
                container.preferences.flush()
                if (closeToTray) windowVisible = false else quit()
            },
            state = windowState,
            visible = windowVisible,
            title = ProductInfo.DISPLAY_NAME,
            icon = appIcon,
            minimumSize = DpSize(windowConfig.minWidth.dp, windowConfig.minHeight.dp),
        ) {
            // `AppFocus` 的兜底（S6 收尾）：旧实现查 AWT `Window.getWindows()`，而 Tao 进程里
            // 没有 AWT 窗口 → 恒判"不在前台"→ 每条通知都弹。这里改挂到 Nucleus 的窗口焦点。
            LaunchedEffect(nucleusWindow) {
                AppFocus.attachProbe { nucleusWindow.isFocused }
            }
            // 窗口内容整体包在应用主题里 —— 标题栏也必须跟着主题走。
            // Tao 的 `TitleBar` 不读 `MaterialTheme`，靠 `LocalTitleBarStyle` 取色；把主题开在
            // 它的外层，再显式传 `style`（见 `shell/window/TitleBarTheme.kt`）。
            val options by AppOptionsHolder.state.collectAsState()
            HermesTheme(darkTheme = options.darkTheme(), style = options.ui.style) {
                // 标题栏（S6）：Tao 的 `DecoratedWindow` **不自带原生标题栏** —— 它在 Windows
                // 上是无边框窗口，标题/图标/最小化/最大化/关闭全靠 `TitleBar` 这个 Composable
                // 画（`dev.nucleusframework.window.TitleBar`，由 `DecoratedWindowScope` 提供）。
                // 它自己处理：拖动整窗、双击最大化、Windows 控制按钮注入、macOS 红绿灯留白。
                // 不调它 → 窗口就是一个没有标题栏、也没法拖动的方块（S6 首次实跑的实际情况）。
                TitleBar(style = rememberHermesTitleBarStyle()) {
                    // Center 区放标题文字；图标与按钮由 Nucleus 处理。
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                AppContent(
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

/**
 * 通知窗的屏幕工作区。
 *
 * 直接用 Tao 的原生显示器信息（`TaoMonitors`），**不需要**窗口句柄 —— 读字节码确认
 * Windows 分支只调 `nativeGetMonitors()`，`taoWindow` 参数默认就是 `null`；
 * 实测 `TaoMonitors.all(null)` 在 `nucleusApplication` 之外也能拿到正确结果
 * （`monitorProbe`：work=IntRect(0,38,2560,1392) scale=1.0）。
 *
 * 早期实现这里用的是 AWT 的 `GraphicsEnvironment` + `Toolkit.getScreenInsets`，
 * 那是业务代码里最后一处"为了一个纯查询去初始化桌面集成层"的调用（ADR-075）。
 */
private fun taoWorkAreaOrNull(): WorkArea? = runCatching {
    val monitor = dev.nucleusframework.window.tao.TaoMonitors.primary(null)
    val px = monitor.workAreaPx
    val scale = monitor.scaleFactor.takeIf { it > 0f } ?: 1f
    WorkArea(
        left = px.left / scale,
        top = px.top / scale,
        width = px.width / scale,
        height = px.height / scale,
    )
}.onFailure {
    Log.warn("notification geometry: Tao monitors unavailable (${it.message})")
}.getOrNull()

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

/** 配置/装配阶段既没有日志、也没有窗口，只能用原生对话框。 */
private fun showFatal(title: String, message: String, silent: Boolean) {
    // 先落 stderr 再弹框：对话框是阻塞的，反过来会让自动化测试永远读不到原因。
    System.err.println("[$title] $message")
    // 静默启动（开机自启）时不该弹模态框卡住会话。
    if (silent || System.getenv(ConfigEnvNames.NO_GUI_DIALOG) == "1") return
    // 原生 `MessageBoxW`：不初始化 AWT/Swing，Compose 没起来时也能弹（ADR-075）。
    NativeMessageBox.error(title, message)
}

/** 配置错误与启动期错误的退出码要能区分（便于脚本判断失败原因）。 */
private const val EXIT_CONFIG_ERROR = 2
private const val EXIT_STARTUP_ERROR = 3
