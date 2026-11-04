package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import dev.shinglem.hermes.core.log.AppLogger
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.log.LogLevel
import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `local` 模式：拉起随包 payload 的 `dashboard` —— 它同时提供 WS JSON-RPC、REST 与 Web UI，
 * 浏览器与客户端共用同一个端口。
 *
 * 它必须是**本进程的前台子进程**（stdout/stderr 全部收集，见 desktop 实现）。
 * 关停只作用于本类持有的 [BackendProcess] 句柄：
 * 不调用 `dashboard --stop`、不扫进程表、不按进程名杀 —— 物理上不可能碰到机器上别的 Hermes。
 */
class LocalBackendProvider(
    private val payloadDir: String,
    private val homeDir: String,
    private val dataDir: String,
    private val token: String,
    private val nonce: String,
    private val parentPid: Long,
    private val parentStartMarker: String,
    private val local: LocalSection,
    private val foreignHomes: List<String>,
    private val launcher: BackendLauncher,
    private val fs: FsProbe,
    private val osEnv: Map<String, String>,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : BackendProvider {

    override val kind: BackendKind = BackendKind.LOCAL

    private val _state = MutableStateFlow<BackendBootState>(BackendBootState.Idle)
    override val state: StateFlow<BackendBootState> = _state.asStateFlow()

    private val buffer = BackendLogBuffer()
    private val _logs = MutableStateFlow<List<BackendLogLine>>(emptyList())
    override val logs: StateFlow<List<BackendLogLine>> = _logs.asStateFlow()

    private var backend: BackendProcess? = null
    private var pumps: MutableList<Job> = mutableListOf()

    override fun currentTarget(): GatewayTarget =
        (state.value as? BackendBootState.Ready)?.target
            // 未就绪时给一个必然连不上的地址：**绝不能**回落到配置里的 remote 地址 ——
            // 那会连到机器上另一个 Hermes（本机就有 9119），把会话串到别人的数据上。
            ?: GatewayTarget(baseUrl = "http://127.0.0.1:0")

    /** Web UI 地址（浏览器里打开 dashboard）。 */
    val dashboardUrl: String? get() = (state.value as? BackendBootState.Ready)?.dashboardUrl

    override suspend fun start() {
        if (state.value is BackendBootState.Ready) return
        stopProcesses()

        if (!LocalBackendPlanner.isSealedPayload(payloadDir, fs::exists)) {
            fail(
                "未找到内嵌 Hermes payload",
                "需要 $payloadDir 下有 manifest.json 与 bin/hermes.exe（sealed payload 判据）",
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
                    "HERMES_HOME=$homeDir 指向既有安装；为免互相污染与误停，拒绝用它启动",
                )
                return
            }
            HomeVerdict.INSIDE_PAYLOAD -> {
                fail("本地数据目录不能放在 payload 内", "HERMES_HOME=$homeDir 位于 $payloadDir 之下")
                return
            }
        }

        fs.mkdirs(homeDir)
        fs.mkdirs(LocalBackendPlanner.path(dataDir, "runtime"))

        // 归属标记：这个 home 属于本 payload。目录已被别的安装用过就拒绝启动，
        // 免得两套数据混在一起、关停时也分不清作用域（见关停安全规格 R1）。
        val marker = LocalBackendPlanner.path(homeDir, OWNER_MARKER)
        val expectedOwner = LocalBackendPlanner.normalize(payloadDir)
        val actualOwner = fs.readTextOrNull(marker)?.trim()
        if (actualOwner != null && actualOwner != expectedOwner) {
            fail("本地数据目录已被另一个 Hermes 占用", "$marker 指向 $actualOwner")
            return
        }
        if (actualOwner == null) fs.writeText(marker, expectedOwner)

        step("启动后端（dashboard）")
        if (!LocalBackendPlanner.hasWebDist(payloadDir, fs::exists)) {
            // 不阻断启动：JSON-RPC / REST 正常，只是浏览器打不开 Web UI。
            logger.log(
                LogLevel.WARN,
                "local backend: payload 缺 hermes_cli/web_dist，Web UI 不可用" +
                    "（重新从 hermes-bundle 的 Release 下载完整 payload）",
            )
        }
        val spec = LocalBackendPlanner.backendSpec(
            payloadDir, homeDir, dataDir, token, nonce, parentPid, parentStartMarker, local, osEnv,
        )
        val port = runProcess(spec, label = "backend") ?: return
        backend = lastLaunched
        // 绑定 0.0.0.0 时本机应该连回环；绑定具体网卡时就连那个地址。
        val connectHost = localConnectHost(local.host)
        logger.log(LogLevel.INFO, "local backend ready on $connectHost:$port")

        // 同一个端口同时是 gateway 与 Web UI：浏览器开 http://<connectHost>:<port> 即可。
        val baseUrl = "http://$connectHost:$port"
        _state.value = BackendBootState.Ready(
            target = GatewayTarget(baseUrl = baseUrl, token = token),
            dashboardUrl = baseUrl,
        )
    }

    override suspend fun stop() {
        stopProcesses()
        _state.value = BackendBootState.Idle
    }

    override fun shutdown() {
        stopProcesses()
        _state.value = BackendBootState.Idle
    }

    // ---------------------------------------------------------------- internals

    private var lastLaunched: BackendProcess? = null

    /**
     * 拉起一条子进程并等它就绪，返回端口；失败时已经把状态置成 [BackendBootState.Failed]。
     *
     * 端口发现以 **ready 文件优先、stdout 哨兵兜底**（`06` §4.3）：文件不受 stdio 缓冲影响。
     */
    private suspend fun runProcess(spec: BackendLaunchSpec, label: String): Int? {
        fs.deleteIfExists(spec.readyFile.orEmpty())
        val process = try {
            launcher.launch(spec)
        } catch (failure: Throwable) {
            fail("无法启动 $label", failure.message ?: failure::class.java.name)
            return null
        }
        lastLaunched = process

        val portSignal = Channel<Int>(Channel.CONFLATED)
        val busySignal = Channel<Int>(Channel.CONFLATED)
        // 子进程的控制台行写 gateway.log（它同时承载 gateway 与 Web UI，见类注释）。
        val sink: (String) -> Unit = { line -> Log.gateway(line) }
        val job = scope.launch(start = CoroutineStart.DEFAULT) {
            process.lines().collect { line ->
                _logs.value = buffer.append(line)
                sink(line.text)
                ReadySentinels.readyPort(line.text)?.let { portSignal.trySend(it) }
                ReadySentinels.portInUse(line.text)?.let { busySignal.trySend(it) }
            }
        }
        pumps += job

        val deadline = spec.timeoutMs
        val outcome: PortOutcome? = withTimeoutOrNull(deadline) {
            var result: PortOutcome? = null
            while (result == null) {
                spec.readyFile?.let { path ->
                    ReadySentinels.readyFilePort(fs.readTextOrNull(path).orEmpty())?.let {
                        result = PortOutcome.Ok(it)
                    }
                }
                if (result == null) portSignal.tryReceive().getOrNull()?.let { result = PortOutcome.Ok(it) }
                if (result == null) busySignal.tryReceive().getOrNull()?.let { result = PortOutcome.Busy(it) }
                if (result == null && !process.isAlive()) result = PortOutcome.Exited
                if (result == null) delay(POLL_MS)
            }
            result
        }

        return when (outcome) {
            null -> {
                fail("等待 $label 就绪超时（${deadline / 1000}s）", logTail())
                process.terminate()
                null
            }
            PortOutcome.Exited -> {
                fail("$label 进程已退出", logTail())
                process.terminate()
                null
            }
            is PortOutcome.Busy -> {
                fail("端口被占用（port=${outcome.port}）", logTail())
                process.terminate()
                null
            }
            is PortOutcome.Ok -> outcome.port
        }
    }

    private sealed interface PortOutcome {
        data class Ok(val port: Int) : PortOutcome
        data class Busy(val port: Int) : PortOutcome
        data object Exited : PortOutcome
    }

    private fun stopProcesses() {
        backend?.terminate(STOP_GRACE_MS)
        backend = null
        pumps.forEach { it.cancel() }
        pumps.clear()
        lastLaunched = null
    }

    private fun step(text: String) {
        _state.value = BackendBootState.Starting(text)
    }

    private fun fail(message: String, detail: String?) {
        logger.log(LogLevel.ERROR, "local backend: $message${detail?.let { " — $it" } ?: ""}")
        _state.value = BackendBootState.Failed(message, detail)
    }

    private fun logTail(lines: Int = 6): String? =
        _logs.value.takeLast(lines).joinToString("\n") { it.text }.ifBlank { null }

    private companion object {
        const val POLL_MS = 150L
        const val STOP_GRACE_MS = 3_000L
        const val OWNER_MARKER = ".hermes-portable-owner"
    }
}

/**
 * 绑定地址 → 本机连接地址。
 *
 * 通配地址（`0.0.0.0` / `::`）不能被"连接"，本机走回环；绑定具体网卡时就连那个地址。
 */
internal fun localConnectHost(bindHost: String): String = when (bindHost.trim()) {
    "", "*", "0.0.0.0", "::", "[::]" -> "127.0.0.1"
    else -> bindHost.trim()
}
