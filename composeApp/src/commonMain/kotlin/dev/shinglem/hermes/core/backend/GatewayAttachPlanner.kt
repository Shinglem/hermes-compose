package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection

/**
 * 把配置翻译成 `hermes gateway ensure --json` / `hermes gateway stop` 的命令行与环境。
 *
 * 与 [LocalBackendPlanner] 的分工：那个管「我们自己 spawn 一个 dashboard 子进程」，
 * 这个管「让上游的机器级 gateway 把后端跑起来，我们只是附着者」。
 *
 * 三条与 isolated 路线**刻意不同**的地方：
 * 1. **不设** `HERMES_PARENT_PID` / `HERMES_DESKTOP_READY_FILE`：daemon 不是我们的子进程，
 *    父进程看门狗与 ready 文件这两套机制它都不认（它用控制 socket + `gateway_state.json`）。
 * 2. **不拼** `local.extraArgs`：那是 dashboard 的参数，放到 `gateway ensure` 后面会被拒。
 *    固定端口同理无效 —— 端口由 daemon 自己决定，通过 endpoint 的 `api_origin` 告诉我们。
 * 3. **不设** `HERMES_WEB_DIST`：gateway listener 会按需挂载 dashboard，用哪份 dist 由它自己定。
 *
 * `HERMES_HOME` 与 `HERMES_DASHBOARD_SESSION_TOKEN` 必须设：
 *  - 前者让 daemon 把数据、日志、控制 socket 全落在我们程序目录内（绿色化 + 隔离的前提）；
 *  - 后者是 loopback 上的 auth gate 豁免凭据（`web_server_boundary._desktop_loopback_auth_exempt`），
 *    缺了它 REST 会 401。
 */
object GatewayAttachPlanner {

    /** 上游 `DEFAULT_ENSURE_TIMEOUT` 是 60s；低于 45s 必然误判。 */
    const val MIN_TIMEOUT_MS = 45_000L

    /** ensure 之后客户端再等的时间：给上游自己的 deadline 留出上报 `starting` 的余量。 */
    const val CLIENT_GRACE_MS = 15_000L

    /** `gateway stop` 是收尾动作，不值得为它卡住退出。 */
    const val STOP_TIMEOUT_MS = 20_000L

    fun ensureSpec(
        payloadDir: String,
        homeDir: String,
        token: String,
        local: LocalSection,
        osEnv: Map<String, String>,
        browserHeaded: Boolean = false,
        browserArgs: String = "",
        elevated: Boolean = false,
    ): BackendLaunchSpec {
        val timeoutMs = local.readyTimeoutMs.coerceAtLeast(MIN_TIMEOUT_MS.toInt()).toLong()
        // 上游的 --timeout 是秒；减一整秒避免边界上两边同时超时。
        val upstreamSeconds = (timeoutMs / 1000 - 1).coerceAtLeast(30)
        return BackendLaunchSpec(
            exe = exePath(payloadDir),
            args = listOf("gateway", "ensure", "--json", "--timeout", upstreamSeconds.toString()),
            env = baseEnv(homeDir, token, local, osEnv, browserHeaded, browserArgs, elevated),
            workDir = LocalBackendPlanner.normalize(payloadDir),
            readyFile = null,
            timeoutMs = timeoutMs + CLIENT_GRACE_MS,
        )
    }

    /**
     * 停掉**本 home** 的 gateway。
     *
     * `-y` 是必需的：上游在非终端环境（我们就是）遇到活跃会话会拒绝并等 y/N 确认。
     * **绝不加 `--all`** —— 那作用于「host gateway」，会停掉别的 profile，甚至官方客户端正在用的后端。
     * 也不能扫进程表：作用域完全由 `HERMES_HOME` 决定，这是唯一安全的边界。
     */
    fun stopSpec(
        payloadDir: String,
        homeDir: String,
        token: String,
        local: LocalSection,
        osEnv: Map<String, String>,
    ): BackendLaunchSpec = BackendLaunchSpec(
        exe = exePath(payloadDir),
        args = listOf("gateway", "stop", "-y"),
        env = baseEnv(homeDir, token, local, osEnv, browserHeaded = false, browserArgs = "", elevated = false),
        workDir = LocalBackendPlanner.normalize(payloadDir),
        readyFile = null,
        timeoutMs = STOP_TIMEOUT_MS,
    )

    fun exePath(payloadDir: String): String = LocalBackendPlanner.path(payloadDir, "bin", "hermes.exe")

    private fun baseEnv(
        homeDir: String,
        token: String,
        local: LocalSection,
        osEnv: Map<String, String>,
        browserHeaded: Boolean,
        browserArgs: String,
        elevated: Boolean,
    ): Map<String, String> = buildMap {
        PASS_THROUGH.forEach { key -> osEnv[key]?.let { put(key, it) } }
        put("HERMES_HOME", homeDir)
        if (token.isNotBlank()) put("HERMES_DASHBOARD_SESSION_TOKEN", token)
        if (local.runCron) put("HERMES_DESKTOP", "1")
        put("PYTHONUTF8", "1")
        put("PYTHONIOENCODING", "utf-8")
        // payload 自带完整运行时，禁止它去联网补依赖（离线/只读目录下会直接失败）。
        put("HERMES_DISABLE_LAZY_INSTALLS", "1")
        if (browserHeaded) put("AGENT_BROWSER_HEADED", "1")
        LocalBackendPlanner.chromiumArgs(elevated = elevated, extra = browserArgs)?.let {
            put("AGENT_BROWSER_ARGS", it)
        }
    }

    /** 与 isolated 路线同一份白名单：不继承 `HDC_*`、代理、云凭据。 */
    private val PASS_THROUGH = listOf(
        "PATH", "PATHEXT", "COMSPEC", "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE",
        "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN",
        "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
        "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS",
    )
}
