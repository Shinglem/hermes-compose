package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.log.LogLevel
import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `local` 模式的 canonical 路线：让上游的**机器级 gateway** 把后端跑起来，我们只做附着者。
 *
 * 与 [LocalBackendProvider]（isolated：自己 spawn `dashboard` 子进程）的区别：
 *  - daemon 由 `hermes gateway ensure` 拉起并 **detach**（脱离我们的 Job Object），
 *    所以「客户端死则后端死」不再成立 —— 必须靠 [stop] 显式收尾；
 *  - 端口不是我们挑的，来自 ensure 输出的 `endpoint.api_origin`；
 *  - 能力（队列、history、steer/rewind）由 owner 侧统一保证，这正是跟随官方路线的收益。
 *
 * 作用域安全：daemon 的 `HERMES_HOME` 由我们注入，`gateway stop` 也只认这个 home。
 * 不扫进程表、不按进程名杀、绝不带 `--all`。
 */
class GatewayAttachBackendProvider(
    private val payloadDir: String,
    private val homeDir: String,
    /** 持久化令牌的目录（客户端自己的数据区，例如 `<home>/data`）。 */
    private val tokenStoreDir: String,
    /**
     * 本次候选令牌。空串表示「配置里没写」——那时会优先沿用持久化的那个，
     * 避免每次启动换 token 让常驻 daemon 认不出来（见 [resolveToken]）。
     */
    private val token: String,
    private val local: LocalSection,
    private val foreignHomes: List<String>,
    private val runner: OneShotRunner,
    private val fs: FsProbe,
    private val osEnv: Map<String, String>,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    /** 候选令牌为空且没有持久化值时，用它生成一个新令牌。 */
    private val newToken: () -> String = { "" },
    private val browserHeaded: Boolean = false,
    private val browserArgs: String = "",
    private val elevated: Boolean = false,
    /** ensure 成功后的回调（桌面实现用它把 endpoint 记进日志/状态栏）。 */
    private val onAttached: (GatewayEndpointInfo) -> Unit = {},
) : BackendProvider {

    override val kind: BackendKind = BackendKind.LOCAL

    /** 实际生效的令牌；[start] 之前是候选值。 */
    private var effectiveToken: String = token

    override val sessionToken: String? get() = effectiveToken.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow<BackendBootState>(BackendBootState.Idle)
    override val state: StateFlow<BackendBootState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<BackendLogLine>>(emptyList())
    override val logs: StateFlow<List<BackendLogLine>> = _logs.asStateFlow()

    private val buffer = BackendLogBuffer()

    private var attached: GatewayEndpointInfo? = null

    /**
     * 串行化 [start] 与 [revalidate] 的 ensure。
     *
     * 两者会**并发**到达：启动时 `backend.start()` 正在跑 ensure，而 `GatewayClient` 的
     * 第 N 次重连失败会回调 `revalidate()`；随后就绪事件又会让 `ShellState` 重启 gateway。
     * 并发时后一次 ensure 会因前面的协程被取消而抛 `CancellationException`，
     * 被 [runEnsure] 当成启动失败 —— 结果是**已经 Ready 的后端被覆盖成 Failed**，
     * 界面上弹出"后端启动失败 gateway ensure"（实测复现）。
     */
    private val ensureMutex = Mutex()

    override fun currentTarget(): GatewayTarget =
        (state.value as? BackendBootState.Ready)?.target
            // 未就绪不给回退地址：连到机器上别的 Hermes 会把会话串到别人的数据上。
            ?: GatewayTarget(baseUrl = "http://127.0.0.1:0")

    override suspend fun start() {
        if (state.value is BackendBootState.Ready) return
        ensureAndAttach()
    }

    /**
     * 强制重跑一次 `gateway ensure`。
     *
     * daemon 的端口由 OS 分配，被外部重启后必然变化；不重新解析就会一直连旧端口，
     * 症状是"连接看起来正常但没有任何事件"（#14）。
     *
     * 已 Ready 时**仍然重跑**：调用它的前提就是"连不上当前地址"，必须重新解析端口。
     */
    override suspend fun revalidate() {
        ensureAndAttach()
    }

    private suspend fun ensureAndAttach() = ensureMutex.withLock {
        if (!LocalBackendPlanner.isSealedPayload(payloadDir, fs::exists)) {
            fail(
                "未找到内嵌 Hermes payload",
                "需要 $payloadDir 下有 manifest.json 与 bin/hermes.exe（sealed payload 判据）",
            )
            return@withLock
        }
        if (!fs.exists(GatewayAttachPlanner.exePath(payloadDir))) {
            fail(
                "payload 不支持 gateway 路线",
                "缺少 ${GatewayAttachPlanner.exePath(payloadDir)}；这不是 hermes 0.23+ 的 payload",
            )
            return@withLock
        }
        when (val verdict = LocalBackendPlanner.classifyHome(homeDir, payloadDir, foreignHomes)) {
            HomeVerdict.OK -> Unit
            HomeVerdict.RELATIVE -> {
                fail("本地数据目录必须是绝对路径", "当前 HERMES_HOME=$homeDir")
                return@withLock
            }
            HomeVerdict.IS_FOREIGN_HOME -> {
                fail(
                    "本地数据目录与其他 Hermes 安装重合",
                    "HERMES_HOME=$homeDir 指向既有安装；为免互相污染，拒绝用它启动",
                )
                return@withLock
            }
            HomeVerdict.INSIDE_PAYLOAD -> {
                fail("本地数据目录不能放在 payload 内", "HERMES_HOME=$homeDir 位于 $payloadDir 之下")
                return@withLock
            }
        }
        fs.mkdirs(homeDir)
        effectiveToken = resolveToken()

        step("连接后端（gateway ensure）")
        var parsed = runEnsure() ?: return@withLock
        if (parsed is EnsureVerdict.Unavailable && GatewayEnsure.isStaleHostOccupancy(parsed)) {
            // 上一轮 gateway 半死（优雅关闭超时被取消）却还占着 host：新 ensure 会以 75 拒绝。
            // 上游给的接管路径就是 `gateway run --replace`，清完场再 ensure 一次（#13）。
            step("接管被占用的后端（host 上有个未发布 served set 的 gateway）")
            takeoverStaleHost()
            parsed = runEnsure() ?: return@withLock
        }

        when (parsed) {
            is EnsureVerdict.Ready -> {
                attached = parsed.endpoint
                onAttached(parsed.endpoint)
                val baseUrl = parsed.endpoint.apiOrigin
                logger.log(
                    LogLevel.INFO,
                    "gateway attached: ${parsed.endpoint.apiOrigin} " +
                        "(instance=${parsed.endpoint.instanceId}, supervisor=${parsed.endpoint.supervisor})",
                )
                _state.value = BackendBootState.Ready(
                    target = GatewayTarget(baseUrl = baseUrl, token = effectiveToken),
                    instanceId = parsed.endpoint.instanceId.takeIf { it.isNotBlank() },
                    supervisor = parsed.endpoint.supervisor.takeIf { it.isNotBlank() },
                    profileId = parsed.endpoint.profileId.takeIf { it.isNotBlank() },
                )
            }

            is EnsureVerdict.Unavailable -> fail(
                diagnose(parsed.state, parsed.reasonCode),
                listOfNotNull(parsed.reasonCode?.let { "reason=$it" }, parsed.detail).joinToString(" — ")
                    .ifBlank { null },
            )

            is EnsureVerdict.Unparseable -> fail("后端握手失败", parsed.detail)
        }
    }

    /** 跑一次 `gateway ensure` 并解析；进程都起不来时返回 null（已 [fail]）。 */
    private suspend fun runEnsure(): EnsureVerdict? {
        val spec = GatewayAttachPlanner.ensureSpec(
            payloadDir = payloadDir,
            homeDir = homeDir,
            token = effectiveToken,
            local = local,
            osEnv = osEnv,
            browserHeaded = browserHeaded,
            browserArgs = browserArgs,
            elevated = elevated,
        )
        val result = try {
            withContext(Dispatchers.Default) { runner.run(spec) }
        } catch (cancelled: CancellationException) {
            // 调用方被取消（例如就绪后 `ShellState` 重启 gateway）：这是**正常收尾**，不是启动失败。
            // 若在这里 fail()，会把已经置好的 Ready 覆盖成 Failed，界面上弹"后端启动失败 gateway ensure"。
            // 必须原样重抛，让取消语义继续传播。
            throw cancelled
        } catch (failure: Throwable) {
            fail("无法执行 gateway ensure", failure.message ?: failure::class.java.name)
            return null
        }
        appendLogs(result.stderr)
        result.stderr.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { Log.gateway(it) }
        return GatewayEnsure.parse(result.stdout, result.exitCode)
    }

    /**
     * 用 `gateway run --replace` 把半死的 owner 顶掉。
     *
     * 命令本身是常驻的，靠 [GatewayAttachPlanner.TAKEOVER_TIMEOUT_MS] 收掉 —— **超时是预期的**，
     * 我们只要它完成"替换"这一步；随后 [runEnsure] 会拉起一个干净的 daemon。
     * 因此这里既不抛也不 fail，只记日志（成功的判据是接下来那次 ensure）。
     */
    private suspend fun takeoverStaleHost() {
        val spec = GatewayAttachPlanner.takeoverSpec(
            payloadDir = payloadDir,
            homeDir = homeDir,
            token = effectiveToken,
            local = local,
            osEnv = osEnv,
            browserHeaded = browserHeaded,
            browserArgs = browserArgs,
            elevated = elevated,
        )
        val result = runCatching { withContext(Dispatchers.Default) { runner.run(spec) } }
            .getOrElse { failure ->
                logger.log(LogLevel.WARN, "gateway takeover 执行失败：${failure.message}")
                return
            }
        appendLogs(result.stderr)
        logger.log(
            LogLevel.INFO,
            "gateway takeover 完成（exit=${result.exitCode}，超时属预期）：" +
                (result.stderrTail() ?: "（无输出）"),
        )
    }

    override suspend fun stop() {
        stopDetached()
        _state.value = BackendBootState.Idle
    }

    override fun shutdown() {
        stopDetached()
        _state.value = BackendBootState.Idle
    }

    // ---------------------------------------------------------------- internals

    /**
     * 决定本次用哪个会话令牌。
     *
     * 顺序（每条都有一次实测事故做后盾）：
     * 1. **`home/.env` 的 `HERMES_DASHBOARD_SESSION_TOKEN`** —— daemon 启动时
     *    `served_profile_child_env(inherit_credentials=True)` 会用 home 的 `.env` **覆盖**我们
     *    注入的值；不跟随它就会「客户端用 A、daemon 用 B」，WS 握手 403 无限重连。
     * 2. **配置里的 `local.token`** —— 用户显式指定的值。
     * 3. **上次持久化的令牌** —— daemon 是常驻进程，重启客户端时不能换令牌。
     * 4. 都没有 → 生成一个并落盘，供下次复用。
     */
    private fun resolveToken(): String {
        val fromEnvFile = GatewayAttachPlanner.envFileToken(fs.readTextOrNull(envFilePath()))
        if (fromEnvFile != null) {
            logger.log(LogLevel.INFO, "gateway attach: 使用 home/.env 里的会话令牌（daemon 以它为准）")
            // 同时落一份：万一 `.env` 以后被删，常驻 daemon 用的还是这个值。
            persistToken(fromEnvFile)
            return fromEnvFile
        }
        if (token.isNotBlank()) return token
        val persisted = fs.readTextOrNull(persistedTokenPath())?.trim()
        if (!persisted.isNullOrEmpty()) return persisted
        val generated = newToken().takeIf { it.isNotBlank() } ?: return ""
        persistToken(generated)
        return generated
    }

    private fun persistToken(value: String) {
        runCatching { fs.writeText(persistedTokenPath(), value) }
            .onFailure { logger.log(LogLevel.WARN, "gateway attach: 令牌落盘失败（$it）") }
    }

    private fun envFilePath(): String = LocalBackendPlanner.path(homeDir, ".env")

    /** 放在我们自己的数据区，不写进 home（那里是上游的地盘）。 */
    private fun persistedTokenPath(): String = LocalBackendPlanner.path(tokenStoreDir, TOKEN_FILE)

    /**
     * 停掉本 home 的 gateway。
     *
     * 只有**我们曾经 attach 过**才停：从没起过就发 `stop` 只会扫描一遍然后失败，纯噪音。
     * 失败不抛 —— 退出路径不能被一个收尾动作卡住；下次启动 ensure 会直接复用它。
     */
    private fun stopDetached() {
        if (attached == null) return
        attached = null
        val spec = GatewayAttachPlanner.stopSpec(
            payloadDir = payloadDir,
            homeDir = homeDir,
            token = token,
            local = local,
            osEnv = osEnv,
        )
        // shutdown() 必须在 commonMain 的同步路径上跑完，所以这里用 runCatching 直接阻塞。
        val result = runCatching { runner.run(spec) }.getOrElse { failure ->
            logger.log(LogLevel.WARN, "gateway stop 失败：${failure.message}")
            return
        }
        if (result.exitCode != 0) {
            logger.log(
                LogLevel.WARN,
                "gateway stop exit=${result.exitCode}：${result.stderrTail() ?: "（无输出）"}",
            )
        } else {
            logger.log(LogLevel.INFO, "gateway stopped (home=$homeDir)")
        }
    }

    private fun step(text: String) {
        _state.value = BackendBootState.Starting(text)
    }

    private fun fail(message: String, detail: String?) {
        logger.log(LogLevel.ERROR, "gateway attach: $message${detail?.let { " — $it" } ?: ""}")
        _state.value = BackendBootState.Failed(message, detail)
    }

    /** ensure 的 stderr 是后端自己的日志（`--quiet` 之后的启动输出），落 gateway.log 便于排障。 */
    private fun appendLogs(stderr: String) {
        if (stderr.isBlank()) return
        for (line in stderr.lineSequence()) {
            _logs.value = buffer.append(BackendLogLine("err", line))
        }
    }

    private fun diagnose(state: String, reasonCode: String?): String = when {
        reasonCode == "deadline" || state == "starting" || state == "absent" ->
            "后端启动超时"

        reasonCode == "authorization" || reasonCode == "gateway_elevated" ||
            reasonCode == "gateway_other_session" || reasonCode == "profile_mismatch" ->
            "无权附着已有后端"

        state == "incompatible" -> "已有后端版本不兼容"
        state == "draining" -> "后端正在重启"
        state == "conflict" -> "后端运行状态冲突"
        state == "inaccessible" -> "无法访问后端运行时"
        else -> "后端未就绪（$state）"
    }

    private companion object {
        /** 持久化的会话令牌：daemon 常驻，重启客户端时令牌必须不变。 */
        const val TOKEN_FILE = "gateway-token"
    }
}
