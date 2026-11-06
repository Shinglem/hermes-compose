package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.log.LogLevel
import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val token: String,
    private val local: LocalSection,
    private val foreignHomes: List<String>,
    private val runner: OneShotRunner,
    private val fs: FsProbe,
    private val osEnv: Map<String, String>,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val browserHeaded: Boolean = false,
    private val browserArgs: String = "",
    private val elevated: Boolean = false,
    /** ensure 成功后的回调（桌面实现用它把 endpoint 记进日志/状态栏）。 */
    private val onAttached: (GatewayEndpointInfo) -> Unit = {},
) : BackendProvider {

    override val kind: BackendKind = BackendKind.LOCAL

    override val sessionToken: String? get() = token.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow<BackendBootState>(BackendBootState.Idle)
    override val state: StateFlow<BackendBootState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<BackendLogLine>>(emptyList())
    override val logs: StateFlow<List<BackendLogLine>> = _logs.asStateFlow()

    private val buffer = BackendLogBuffer()

    private var attached: GatewayEndpointInfo? = null

    override fun currentTarget(): GatewayTarget =
        (state.value as? BackendBootState.Ready)?.target
            // 未就绪不给回退地址：连到机器上别的 Hermes 会把会话串到别人的数据上。
            ?: GatewayTarget(baseUrl = "http://127.0.0.1:0")

    override suspend fun start() {
        if (state.value is BackendBootState.Ready) return

        if (!LocalBackendPlanner.isSealedPayload(payloadDir, fs::exists)) {
            fail(
                "未找到内嵌 Hermes payload",
                "需要 $payloadDir 下有 manifest.json 与 bin/hermes.exe（sealed payload 判据）",
            )
            return
        }
        if (!fs.exists(GatewayAttachPlanner.exePath(payloadDir))) {
            fail(
                "payload 不支持 gateway 路线",
                "缺少 ${GatewayAttachPlanner.exePath(payloadDir)}；这不是 hermes 0.23+ 的 payload",
            )
            return
        }
        when (val verdict = LocalBackendPlanner.classifyHome(homeDir, payloadDir, foreignHomes)) {
            HomeVerdict.OK -> Unit
            HomeVerdict.RELATIVE -> {
                fail("本地数据目录必须是绝对路径", "当前 HERMES_HOME=$homeDir")
                return
            }
            HomeVerdict.IS_FOREIGN_HOME -> {
                fail(
                    "本地数据目录与其他 Hermes 安装重合",
                    "HERMES_HOME=$homeDir 指向既有安装；为免互相污染，拒绝用它启动",
                )
                return
            }
            HomeVerdict.INSIDE_PAYLOAD -> {
                fail("本地数据目录不能放在 payload 内", "HERMES_HOME=$homeDir 位于 $payloadDir 之下")
                return
            }
        }
        fs.mkdirs(homeDir)

        step("连接后端（gateway ensure）")
        val spec = GatewayAttachPlanner.ensureSpec(
            payloadDir = payloadDir,
            homeDir = homeDir,
            token = token,
            local = local,
            osEnv = osEnv,
            browserHeaded = browserHeaded,
            browserArgs = browserArgs,
            elevated = elevated,
        )
        val result = try {
            withContext(Dispatchers.Default) { runner.run(spec) }
        } catch (failure: Throwable) {
            fail("无法执行 gateway ensure", failure.message ?: failure::class.java.name)
            return
        }
        appendLogs(result.stderr)
        result.stderr.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { Log.gateway(it) }

        when (val parsed = GatewayEnsure.parse(result.stdout, result.exitCode)) {
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
                    target = GatewayTarget(baseUrl = baseUrl, token = token),
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
}
