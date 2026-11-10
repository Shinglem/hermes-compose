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

    /** 接管动作只用来"清场"，不需要它把 daemon 跑起来，所以超时给得短。 */
    const val TAKEOVER_TIMEOUT_MS = 20_000L

    /**
     * 接管被半死 gateway 占着的 host（`gateway run --replace`）。
     *
     * 上游在检测到 host 已被占用、但 owner 的 control socket 不应答时**故意**以 75 拒绝启动，
     * 并在提示里给出这条接管路径（`gateway/host_attach.py::_unknown_served_message`）。
     *
     * 这条命令是前台常驻的（它就是 daemon 本体），所以**必须**靠 `timeoutMs` 收掉：
     * 我们只借它把 stale owner 干掉，随后再由 ensure 重新拉起一个干净的。
     * 因此超时不算失败；只有旧 owner 仍在时才继续报错。
     */
    fun takeoverSpec(
        payloadDir: String,
        homeDir: String,
        token: String,
        local: LocalSection,
        osEnv: Map<String, String>,
        browserHeaded: Boolean = false,
        browserArgs: String = "",
        elevated: Boolean = false,
    ): BackendLaunchSpec = BackendLaunchSpec(
        exe = exePath(payloadDir),
        args = listOf("gateway", "run", "--replace"),
        env = baseEnv(homeDir, token, local, osEnv, browserHeaded, browserArgs, elevated),
        workDir = LocalBackendPlanner.normalize(payloadDir),
        readyFile = null,
        timeoutMs = TAKEOVER_TIMEOUT_MS,
    )

    /**
     * 从 `HERMES_HOME/.env` 取上游真正会用的会话令牌。
     *
     * **为什么必须读它**：`gateway ensure` 起 daemon 时走
     * `served_profile_child_env(base=os.environ, target_home=home, inherit_credentials=True)`，
     * 它会用 home 的 `.env` **覆盖**我们在 env 里注入的 `HERMES_DASHBOARD_SESSION_TOKEN`。
     * 于是出现「客户端随机生成 A、daemon 用 .env 里的 B」→ WS 握手 403、无限重连（实测踩过）。
     *
     * 优先级：`home/.env` 有值就用它（那是 daemon 必然采用的值）；否则用我们生成的。
     * 解析刻意保守：只认 `KEY=value` 行，去掉成对引号与 `export ` 前缀，值里允许 `=`。
     */
    fun envFileToken(envFileText: String?): String? {
        if (envFileText.isNullOrBlank()) return null
        val key = "HERMES_DASHBOARD_SESSION_TOKEN"
        for (raw in envFileText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = if (line.startsWith("export ")) line.removePrefix("export ").trim() else line
            val eq = body.indexOf('=')
            if (eq <= 0) continue
            if (!body.substring(0, eq).trim().equals(key, ignoreCase = true)) continue
            val value = body.substring(eq + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
            return value.takeIf { it.isNotEmpty() }
        }
        return null
    }


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
