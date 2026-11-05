package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection

/** 一次子进程启动所需的全部参数（纯数据，便于单测断言）。 */
data class BackendLaunchSpec(
    val exe: String,
    val args: List<String>,
    val env: Map<String, String>,
    val workDir: String,
    val readyFile: String?,
    val timeoutMs: Long,
)

/** home 归属判定结果（关停安全规格 R1）。 */
enum class HomeVerdict {
    OK,

    /** 相对路径 / 空 —— 子进程会回落到 `~/.hermes`，把用户家目录写脏。 */
    RELATIVE,

    /** 指向另一个 Hermes 安装的 home；用它启动会与用户既有实例共用作用域。 */
    IS_FOREIGN_HOME,

    /** 落在 payload 内部：升级替换 payload 时会把数据一起删掉。 */
    INSIDE_PAYLOAD,
}

/**
 * 把配置翻译成两条子进程的命令行与环境（`06-本地后端与双模式.md` §4.1 / §4.2）。
 *
 * 这里只做纯计算，不碰文件系统 —— 存在性校验与父进程 PID 之类的运行时事实
 * 由 desktop 侧的 launcher 注入，本类因此可以直接单测。
 */
object LocalBackendPlanner {
    const val DEFAULT_TIMEOUT_MS = 90_000L
    const val MIN_TIMEOUT_MS = 45_000L

    /**
     * 已知的「别人的 home」。命中任一即拒绝启动：
     * 与它们共用作用域会让 `serve` 的会话/配置互相污染，也让关停无法按作用域隔离。
     */
    fun foreignHomes(userHome: String, appData: String?, localAppData: String?): List<String> = buildList {
        if (userHome.isNotBlank()) add(path(userHome, ".hermes"))
        appData?.takeIf { it.isNotBlank() }?.let { add(path(it, "hermes")) }
        localAppData?.takeIf { it.isNotBlank() }?.let { add(path(it, "hermes")) }
    }

    fun classifyHome(home: String, payloadDir: String, foreign: List<String>): HomeVerdict {
        if (home.isBlank() || !isAbsolute(home)) return HomeVerdict.RELATIVE
        val normalized = normalize(home)
        if (normalize(payloadDir).let { normalized == it || normalized.startsWith("$it/") }) {
            return HomeVerdict.INSIDE_PAYLOAD
        }
        if (foreign.any { normalize(it) == normalized }) return HomeVerdict.IS_FOREIGN_HOME
        return HomeVerdict.OK
    }

    /**
     * 后端只有**一个**进程：`dashboard`。
     *
     * `serve` 与 `dashboard` 提供同一套 WS JSON-RPC + REST（上游 help：`serve` 是
     * "Start the Hermes backend server (headless; powers the desktop app and remote backends)"），
     * 区别只有 `dashboard` 额外提供 Web UI 静态资源。既然客户端和浏览器可以共用同一个端口，
     * 就没有理由起两个进程 —— 也就不需要 `serve`。
     *
     * `--no-open`：别让它自己拉起系统浏览器，界面在客户端里。
     * `--skip-build`：运行时不构建前端（要几十秒且要 npm 依赖）；payload 必须自带 `web_dist`，
     * 由 `bundle-windows` 构建时保证，[hasWebDist] 用于启动前自检。
     * `--isolated`：**必须加**。缺了它，机器上已有别的 dashboard（例如官方桌面端占着 9119）时，
     * 新起的进程会 attach 到那台 host 后端并 exit 0（`Hermes dashboard already running on this host`），
     * 我们的就绪判定只能看到"进程立刻退出"，本地后端就永远起不来（实测）。
     * `--isolated` 同时跳过 `_route_named_profile_dashboard` 的机器级重定向 —— 我们要的就是本实例自己的后端。
     */
    fun backendSpec(
        payloadDir: String,
        homeDir: String,
        dataDir: String,
        token: String,
        nonce: String,
        parentPid: Long,
        parentStartMarker: String,
        local: LocalSection,
        osEnv: Map<String, String>,
        /** 让 agent 的浏览器可见（`AGENT_BROWSER_HEADED=1`，见 `BrowserSection.headed`）。 */
        browserHeaded: Boolean = false,
        /** 追加的 Chromium 启动参数（`BrowserSection.args`）。 */
        browserArgs: String = "",
        /** 本进程是否以管理员令牌运行 —— 决定是否自动补 `--no-sandbox`。 */
        elevated: Boolean = false,
    ): BackendLaunchSpec = spec(
        payloadDir = payloadDir,
        homeDir = homeDir,
        dataDir = dataDir,
        token = token,
        nonce = nonce,
        parentPid = parentPid,
        parentStartMarker = parentStartMarker,
        local = local,
        osEnv = osEnv,
        command = "dashboard",
        extraArgs = listOf(
            "--host", local.host.ifBlank { LocalSection.DEFAULT_HOST },
            // 越界值回落成「让系统分配」，别把一段非法端口传给后端（配置层也拦，这里是防御）。
            "--port", (local.port.takeIf { it in 0..65535 } ?: 0).toString(),
            "--skip-build", "--no-open", "--isolated",
        ),
        readyFile = readyFile(dataDir, "ready-$nonce.json"),
        browserHeaded = browserHeaded,
        browserArgs = browserArgs,
        elevated = elevated,
    )

    /**
     * payload 是否带 Web UI 静态资源。
     *
     * 缺了不影响 JSON-RPC / REST（客户端照常工作），但浏览器打开同一端口会是占位页 ——
     * 状态栏的「服务」一项就靠这个显示无响应，所以启动时先判一次并写进日志。
     */
    fun hasWebDist(payloadDir: String, exists: (String) -> Boolean): Boolean =
        exists(path(payloadDir, "hermes-agent", "hermes_cli", "web_dist", "index.html"))

    fun readyFile(dataDir: String, name: String): String = path(dataDir, "runtime", name)

    /** `bin/hermes.exe`：**不要**用 `hermes.cmd`（cmd 黑框 + base64 包装）。 */
    fun exePath(payloadDir: String): String = path(payloadDir, "bin", "hermes.exe")

    fun isSealedPayload(payloadDir: String, exists: (String) -> Boolean): Boolean =
        exists(path(payloadDir, "manifest.json")) && exists(exePath(payloadDir))

    private fun spec(
        payloadDir: String,
        homeDir: String,
        dataDir: String,
        token: String,
        nonce: String,
        parentPid: Long,
        parentStartMarker: String,
        local: LocalSection,
        osEnv: Map<String, String>,
        command: String,
        extraArgs: List<String>,
        readyFile: String,
        browserHeaded: Boolean = false,
        browserArgs: String = "",
        elevated: Boolean = false,
    ): BackendLaunchSpec {
        val env = buildMap {
            // OS 运行必需项：不继承环境，但要能启动进程（白名单，见 06 §4.2）。
            PASS_THROUGH.forEach { key -> osEnv[key]?.let { put(key, it) } }
            put("HERMES_HOME", homeDir)
            put("HERMES_DASHBOARD_SESSION_TOKEN", token)
            if (local.runCron) put("HERMES_DESKTOP", "1")
            put("HERMES_PARENT_PID", parentPid.toString())
            put("HERMES_PARENT_START_MARKER", parentStartMarker)
            put("HERMES_PARENT_NONCE", nonce)
            put("TERMINAL_CWD", normalize(payloadDir))
            put("PYTHONPATH", "")
            put("PYTHONHOME", "")
            put("PYTHONUTF8", "1")
            put("HERMES_DISABLE_LAZY_INSTALLS", "1")
            put("HERMES_DESKTOP_READY_FILE", readyFile)
            // agent 操作的浏览器是否可见（#9）。用环境变量而不是改后端 config.yaml：
            // `PUT /api/config/raw` 是整篇 YAML 替换，写坏会毁掉用户整份配置。
            if (browserHeaded) put("AGENT_BROWSER_HEADED", "1")            // HERMES_WEB_DIST 刻意不设：客户端只用 JSON-RPC + REST。
            chromiumArgs(elevated = elevated, extra = browserArgs)?.let { put("AGENT_BROWSER_ARGS", it) }
        }
        return BackendLaunchSpec(
            exe = exePath(payloadDir),
            args = listOf(command) + extraArgs + local.extraArgs,
            env = env,
            workDir = normalize(payloadDir),
            readyFile = readyFile,
            timeoutMs = local.readyTimeoutMs.coerceAtLeast(MIN_TIMEOUT_MS.toInt()).toLong(),
        )
    }

    /** 只有这些键从父进程透传；其余（`HDC_*`、代理、云凭据）一律不继承。 */
    private val PASS_THROUGH = listOf(
        "PATH", "PATHEXT", "COMSPEC", "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE",
        "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN",
        "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
        "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS",
    )

    /** 提权运行时 Chromium 必需的绕过参数：管理员令牌下它拒绝启用自身沙箱。 */
    val SANDBOX_BYPASS_ARGS = listOf("--no-sandbox", "--disable-dev-shm-usage")

    /**
     * 合并出 `AGENT_BROWSER_ARGS`；两段都为空时返回 null（**不设**这个变量）。
     *
     * 自动段只在 [elevated] 时加：Chromium 在管理员令牌下会拒绝启用沙箱，
     * 不加就在写 `DevToolsActivePort` 前 `exit code 0` 静默退出
     * （后端 `_needs_chromium_sandbox_bypass()` 只判 Linux root / Docker / AppArmor，
     * Windows 上恒 false，指望不上它）。非提权时不需要，也就不主动降低隔离。
     *
     * 用户段与自动段**合并去重**而不是二选一：用户填 `--remote-debugging-port=…`
     * 时不能把 `--no-sandbox` 挤掉，否则浏览器又起不来。
     */
    fun chromiumArgs(elevated: Boolean, extra: String): String? {
        val parts = buildList {
            if (elevated) addAll(SANDBOX_BYPASS_ARGS)
            extra.split(',', '\n', '\r').map { it.trim() }.filterTo(this) { it.isNotEmpty() }
        }.distinct()
        return parts.joinToString(",").takeIf { it.isNotEmpty() }
    }

    private fun isAbsolute(value: String): Boolean =
        value.startsWith("/") || value.startsWith("\\") || Regex("""^[A-Za-z]:[\\/].*""").matches(value)

    fun normalize(value: String): String = value.replace('\\', '/').trimEnd('/')

    fun path(vararg parts: String): String = parts
        .filter { it.isNotBlank() }
        .joinToString("/") { it.replace('\\', '/').trimEnd('/') }
}
