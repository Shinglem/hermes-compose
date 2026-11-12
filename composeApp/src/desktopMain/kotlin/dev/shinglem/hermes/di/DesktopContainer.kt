package dev.shinglem.hermes.di

import dev.shinglem.hermes.core.app.DesktopAppRestarter
import dev.shinglem.hermes.core.app.Elevation
import dev.shinglem.hermes.core.app.OfficialDesktopLauncherImpl
import dev.shinglem.hermes.core.app.ScheduledAutostart
import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.backend.DesktopBackendLauncher
import dev.shinglem.hermes.core.backend.DesktopFsProbe
import dev.shinglem.hermes.core.backend.DesktopOneShotRunner
import dev.shinglem.hermes.core.backend.GatewayAttachBackendProvider
import dev.shinglem.hermes.core.backend.LocalBackendPlanner
import dev.shinglem.hermes.core.backend.LocalBackendProvider
import dev.shinglem.hermes.core.backend.RemoteBackendProvider
import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.BackendMode
import dev.shinglem.hermes.core.config.ConfigError
import dev.shinglem.hermes.core.config.ConfigLoader
import dev.shinglem.hermes.core.config.ConfigPaths
import dev.shinglem.hermes.core.config.ElevateMode
import dev.shinglem.hermes.core.config.FileSettingsStore
import dev.shinglem.hermes.core.config.LoadedConfig
import dev.shinglem.hermes.core.config.LocalEngine
import dev.shinglem.hermes.core.config.OptionsWatcher
import dev.shinglem.hermes.core.config.SystemConfigSource
import dev.shinglem.hermes.core.config.TokenSource
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.Logging
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.log.LogLevel
import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.core.prefs.JsonFileSettings
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.GatewayTarget
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.feature.capabilities.CapabilityRepository
import dev.shinglem.hermes.feature.chat.ChatRepository
import dev.shinglem.hermes.feature.composer.ComposerRepository
import dev.shinglem.hermes.feature.model.ModelRepository
import dev.shinglem.hermes.feature.session.SessionRepository
import dev.shinglem.hermes.core.backend.CdpProbe
import dev.shinglem.hermes.feature.settings.BrowserArgsReconciler
import dev.shinglem.hermes.feature.settings.ConfigRepository
import dev.shinglem.hermes.feature.settings.CustomEndpointRepository
import dev.shinglem.hermes.feature.settings.MaintenanceRepository
import dev.shinglem.hermes.feature.settings.ModelAdminRepository
import dev.shinglem.hermes.feature.settings.ProfilesAdminRepository
import dev.shinglem.hermes.feature.settings.ProviderRepository
import dev.shinglem.hermes.feature.shell.ProfileRepository
import dev.shinglem.hermes.feature.terminal.TerminalReadPayload
import dev.shinglem.hermes.feature.terminal.TerminalRepository
import dev.shinglem.hermes.feature.terminal.TerminalViewModel
import dev.shinglem.hermes.terminal.terminalRead
import java.io.File
import java.security.SecureRandom
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/** 桌面端装配：配置、偏好、日志、传输层与 Repository 各一份。 */
object DesktopContainer {
    /**
     * 装配顺序有硬约束：**先读配置 → 再装日志**。
     *
     * 配置解析失败时日志还没就绪，只能靠 `JOptionPane` 报（见 `Main.kt`）；
     * 所以这里把 [ConfigError] 原样抛给调用方，不自己弹窗、也不吞。
     */
    fun build(): AppContainer {
        val source = SystemConfigSource
        val paths = ConfigPaths.resolve(source)

        // 未知键只告警，但此刻 logger 还没装 —— 先收集，装好再回放。
        val pendingWarnings = mutableListOf<String>()
        val loaded = ConfigLoader(source) { pendingWarnings += it }.load(paths)
        val config = loaded.config

        Logging.init(
            logDir = logDir(loaded),
            minLevel = logLevelOf(config),
            alsoStderr = config.app.log.alsoStderr,
        )
        val logger = Logging.desktop
        if (loaded.generatedDefault) {
            Log.info("未找到 ${paths.configFile.absolutePath}，已生成默认配置")
        }
        Log.info("配置：${paths.configFile.absolutePath}（home=${paths.home.absolutePath}）")
        Log.info(
            "后端：${config.backend.remote.baseUrl}（mode=${config.backend.mode.name.lowercase()}，" +
                "token=${config.backend.remote.token.source.name.lowercase()}，" +
                "日志=${config.app.logLevel}）",
        )
        pendingWarnings.forEach { Log.warn(it) }

        val stateSettings = JsonFileSettings(paths.stateFile)
        migrateRegistryIfNeeded(paths, stateSettings)
        val preferences = AppPreferences(stateSettings) { stateSettings.flush() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

        // options.json 热生效：启动时先应用一次，之后改了文件立即跟。
        // watcher 复用同一个 scope，AppContainer.close() 里 scope.cancel() 会连带停掉它。
        OptionsWatcher(paths, scope) { message -> Log.warn(message) }.start()

        val backend = buildBackend(config, paths, logger, scope)
        val targetProvider = { backend.currentTarget() }

        val gateway = GatewayClient(targetProvider, Logging.gateway, scope) {
            // 连续连不上时重新 ensure：daemon 被外部重启会换端口（OS 分配），
            // 只重试旧 URL 永远连不上，症状是"发消息没反应"（#14）。
            backend.revalidate()
        }
        val restClient = RestClient(targetProvider, Logging.gateway)

        val terminalViewModel = TerminalViewModel(preferences)
        val terminalRepository = TerminalRepository(gateway)
        // `terminal.read` 真正的实现：读激活标签的缓冲区（BossTerm 的 textBuffer）。
        terminalViewModel.readBuffer = { start, count ->
            val activeId = terminalViewModel.state.value.layout.activeTabId.orEmpty()
            terminalRead(activeId, start, count)?.let { snapshot ->
                TerminalReadPayload(
                    totalLines = snapshot.totalLines,
                    start = snapshot.start,
                    end = snapshot.end,
                    viewportRows = snapshot.viewportRows,
                    cursorRow = snapshot.cursorRow,
                    text = snapshot.text,
                )
            }
        }
        terminalRepository.register(terminalViewModel, scope)
        // `terminal.resize`：终端可见列数变化时上报（best-effort，失败只记日志）。
        terminalViewModel.reportCols = { sessionId, cols ->
            scope.launch { terminalRepository.reportCols(sessionId, cols) }
        }

        return AppContainer(
            gateway = gateway,
            restClient = restClient,
            preferences = preferences,
            sessionRepository = SessionRepository(gateway, restClient),
            profileRepository = ProfileRepository(gateway),
            chatRepository = ChatRepository(gateway, restClient),
            composerRepository = ComposerRepository(gateway),
            modelRepository = ModelRepository(gateway, restClient),
            capabilityRepository = CapabilityRepository(restClient),
            providerRepository = ProviderRepository(restClient),
            customEndpointRepository = CustomEndpointRepository(restClient),
            profilesAdminRepository = ProfilesAdminRepository(restClient),
            modelAdminRepository = ModelAdminRepository(restClient),
            configRepository = ConfigRepository(restClient),
            browserArgsReconciler = BrowserArgsReconciler(
                config = ConfigRepository(restClient),
                profiles = ProfilesAdminRepository(restClient),
                cdpAlive = { url -> CdpProbe.isReachable(url) },
            ),
            maintenanceRepository = MaintenanceRepository(restClient),
            officialDesktop = OfficialDesktopLauncherImpl.detect(
                config = config,
                paths = paths,
                // 延迟求值：LOCAL 模式下后端端口要等 Ready 后才知道（未就绪时是 `:0` 占位，
                // 由 officialDesktopEnv 的 isUsableBackendUrl 过滤掉）。
                backendUrl = { backend.currentTarget().baseUrl },
                sessionToken = { backend.sessionToken },
            ),
            terminalViewModel = terminalViewModel,
            terminalRepository = terminalRepository,
            logger = logger,
            scope = scope,
            config = config,
            hermesHome = paths.hermesHomeDir(config).absolutePath,
            backend = backend,
            settingsStore = FileSettingsStore(paths) { message -> Log.warn(message) },
            restarter = DesktopAppRestarter(
                backend = backend,
                logDir = paths.logDir(config, paths.dataDir(config)),
            ),
            elevated = Elevation.isElevated(),
            elevateRequested = config.app.elevate == ElevateMode.ASK,
            deleteOrphanTask = { name -> ScheduledAutostart.deleteTask(name) },
        )
    }

    /**
     * 按 `backend.mode` 选实现。
     *
     * `local` 的 `HERMES_HOME` 取 `<home>/hermes-home`（本程序独占）：它决定后端子进程的
     * 作用域，也决定关停时能碰到谁 —— 详见 `hermes-core/15-关停安全规格.md`。
     */
    private fun buildBackend(
        config: AppConfig,
        paths: ConfigPaths,
        logger: AppLogger,
        scope: CoroutineScope,
    ): BackendProvider {
        val fallback = GatewayTarget(config.backend.remote.baseUrl, resolveToken(config))
        return when (config.backend.mode) {
            BackendMode.REMOTE -> RemoteBackendProvider(fallback, resolveToken(config))

            BackendMode.LOCAL -> when (config.backend.local.engine) {
                LocalEngine.GATEWAY -> GatewayAttachBackendProvider(
                    payloadDir = paths.hermesDir(config).absolutePath,
                    homeDir = paths.hermesHomeDir(config).absolutePath,
                    tokenStoreDir = paths.dataDir(config).absolutePath,
                    // 空串 = 配置里没写；此时 provider 会优先沿用持久化令牌，
                    // 避免每次启动换 token 让常驻 daemon 认不出来。
                    token = config.backend.local.token?.takeIf { it.isNotBlank() }.orEmpty(),
                    newToken = { randomHex(16) },
                    local = config.backend.local,
                    foreignHomes = foreignHomes(),
                    runner = DesktopOneShotRunner,
                    fs = DesktopFsProbe,
                    osEnv = System.getenv(),
                    logger = logger,
                    scope = scope,
                    browserHeaded = config.app.browser.headed,
                    browserArgs = config.app.browser.args,
                    elevated = Elevation.isElevated(),
                )

                LocalEngine.ISOLATED -> LocalBackendProvider(
                    payloadDir = paths.hermesDir(config).absolutePath,
                    homeDir = paths.hermesHomeDir(config).absolutePath,
                    dataDir = paths.dataDir(config).absolutePath,
                    token = config.backend.local.token?.takeIf { it.isNotBlank() } ?: randomHex(16),
                    nonce = randomHex(8),
                    parentPid = ProcessHandle.current().pid(),
                    // `winms:` 必须是**父进程的创建时间**（Unix 毫秒），不是当前时间：
                    // 后端拿它和真实 FILETIME 比，不相等就判定「桌面端已被替换」并自杀。
                    // 之前传 System.currentTimeMillis() 导致启动了约 2 秒就被 watchdog 收掉，
                    // 客户端于是一路重连。
                    parentStartMarker = "winms:" + parentStartMillis(),
                    local = config.backend.local,
                    foreignHomes = foreignHomes(),
                    launcher = DesktopBackendLauncher(),
                    fs = DesktopFsProbe,
                    osEnv = System.getenv(),
                    logger = logger,
                    scope = scope,
                    browserHeaded = config.app.browser.headed,
                    browserArgs = config.app.browser.args,
                    elevated = Elevation.isElevated(),
                )
            }
        }
    }

    private fun foreignHomes(): List<String> = LocalBackendPlanner.foreignHomes(
        userHome = System.getProperty("user.home").orEmpty(),
        appData = System.getenv("APPDATA"),
        localAppData = System.getenv("LOCALAPPDATA"),
    )

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    /**
     * 本进程的创建时间（Unix 毫秒），供 `HERMES_PARENT_START_MARKER` 用。
     *
     * 后端会把它与父进程的真实 FILETIME 换算后比较，不一致即判定父进程已死并自杀。
     * 平台不给这个值（`startInstant()` 为空）时返回 0 —— 后端会把 `winms:0` 当作
     * 无法证明的标记，宁可放弃父进程跟踪也不会自杀。
     */
    private fun parentStartMillis(): Long =
        ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(0L)

    /**
     * 一次性迁移：老的 `PreferencesSettings` 把偏好写进注册表
     * `HKCU\Software\JavaSoft\Prefs\dev\shinglem\hermes`，绿色版不该再碰注册表。
     * `state.json` 不存在且注册表里有键时搬一次，成功后删掉注册表节点。
     *
     * 键名两边一致（都是 `PrefKeys` 那套），所以逐个搬字符串即可。
     */
    private fun migrateRegistryIfNeeded(paths: ConfigPaths, settings: JsonFileSettings) {
        if (paths.stateFile.isFile) return
        val root = Preferences.userRoot()
        val node = root.node(NODE)
        val keys = runCatching { node.keys() }.getOrNull() ?: return
        if (keys.isEmpty()) return

        var migrated = 0
        for (key in keys) {
            val value = runCatching { node.get(key, null) }.getOrNull() ?: continue
            settings.putString(key, value)
            migrated++
        }
        if (migrated == 0) return
        settings.flush()
        // 只删自己那一层：`removeNode()` 会连带删掉所有子节点，
        // 若是删 `userRoot()` 就等于把整个 HKCU\Software\JavaSoft\Prefs 清空（别的程序还在用）。
        runCatching { node.removeNode() }.onFailure {
            Log.warn("注册表节点 ${node.absolutePath()} 删除失败（旧值仍在，迁移已完成）：${it.message}")
        }
        Log.info("已把 $migrated 条偏好从注册表迁移到 ${paths.stateFile.absolutePath}")
    }

    private fun logLevelOf(config: AppConfig): LogLevel {
        val wanted = config.app.logLevel.trim().uppercase()
        return LogLevel.entries.firstOrNull { it.name == wanted } ?: LogLevel.INFO
    }

    private fun logDir(loaded: LoadedConfig): String {
        val dataDir = loaded.paths.dataDir(loaded.config)
        return loaded.paths.logDir(loaded.config, dataDir).absolutePath
    }

    /**
     * 凭据解析。
     *
     * - `inline`：配置里直接写
     * - `env`：读配置指定的环境变量名
     * - `env_file`：给了路径就读它；没给则按「`$HERMES_HOME/.env` → `~/.hermes/.env`」兜底
     *   （与官方 dashboard 同一来源，避免把 token 明文存进偏好文件）
     */
    private fun resolveToken(config: AppConfig): String? {
        val token = config.backend.remote.token
        return when (token.source) {
            TokenSource.INLINE -> token.value?.takeIf { it.isNotBlank() }

            TokenSource.ENV -> token.value
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> System.getenv(name)?.takeIf { it.isNotBlank() } }

            TokenSource.ENV_FILE -> {
                val candidates = buildList {
                    token.value?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
                    System.getenv("HERMES_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it, ".env")) }
                    add(File(System.getProperty("user.home"), ".hermes/.env"))
                }
                candidates.firstOrNull { it.isFile }?.let(::readToken)
            }
        }
    }

    private fun readToken(envFile: File): String? = runCatching {
        envFile.readLines(Charsets.UTF_8)
            .firstOrNull { it.trimStart().startsWith("$TOKEN_ENV=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private const val NODE = "dev/shinglem/hermes"
    private const val TOKEN_ENV = "HERMES_DASHBOARD_SESSION_TOKEN"
}
