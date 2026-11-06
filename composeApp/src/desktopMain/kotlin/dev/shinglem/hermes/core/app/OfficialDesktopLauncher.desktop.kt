package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.ConfigPaths
import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * 官方 Electron 桌面端（debug-only，v1.0.0 移除；见 ADR-045）。
 *
 * 查找顺序：
 * 1. `config.json` 的 `app.debug.official_desktop`（绝对路径，或相对 `<home>`）
 * 2. payload 下的 `official-desktop/Hermes.exe`（CI 打进来的对照包）
 *
 * 两处都没有 → [detect] 返回 null，设置页不出现「集成 → 官方桌面端」。
 */
class OfficialDesktopLauncherImpl(
    private val exe: File,
    private val dataRoot: File,
    /** 后端地址取值器。**延迟求值**：LOCAL 模式要等后端 Ready 后端口才确定。 */
    private val backendUrl: () -> String?,
    /** 后端会话令牌取值器；与后端子进程收到的 `HERMES_DASHBOARD_SESSION_TOKEN` 是同一个。 */
    private val sessionToken: () -> String?,
    /** 探活：返回 true 表示后端真的在响应。测试注入。 */
    private val probe: (String, Long) -> Boolean = ::probeStatus,
    /** 真正起进程。测试注入以断言「不可用时不启动任何进程」。 */
    private val starter: (File, File, Map<String, String>) -> Unit = ::startDetached,
) : OfficialDesktopLauncher {

    override val path: String get() = exe.absolutePath

    override val dataDir: String get() = dataRoot.absolutePath

    override val sharedBackend: SharedBackendState
        get() {
            val url = backendUrl()?.trim().orEmpty()
            if (!isUsableBackendUrl(url)) {
                return SharedBackendState.Unavailable("后端还没就绪（等状态栏显示「网关 xxms」）")
            }
            val token = sessionToken()?.trim().orEmpty()
            if (token.isEmpty()) {
                return SharedBackendState.Unavailable("后端没有可用凭据，官方桌面端无法附着")
            }
            return SharedBackendState.Ready(url)
        }

    /**
     * 起一个独立进程，并保证它连的是**我们这一个**后端。
     *
     * 四件事都是实测出来的，缺一件对照包就起不来、进向导或拖垮本机官方客户端：
     * - `HERMES_DESKTOP_USER_DATA_DIR`：官方客户端按 userData 抢单实例锁，共用时新实例
     *   打印 `another Hermes Desktop instance holds the single-instance lock; exiting` 后 exit 0；
     * - `HERMES_HOME`：不隔离就会复用用户本机 home 并拉起生产后端，污染真实数据；
     * - `HERMES_DESKTOP_REMOTE_URL` + `HERMES_DESKTOP_REMOTE_TOKEN`：不给就会走"自己拉起后端"
     *   的本地路径，于是停在"配置后端"向导里（两个必须同时给，只给 URL 会直接抛错）；
     * - 输出重定向到文件：`ProcessBuilder` 的管道没人读，写满 64 KiB 后子进程会卡住。
     *
     * 因此**不可用就拒绝启动**：静默省略那两个变量等于让它另起一套后端，
     * 与「共用同一个后端」的目的一开始就背离了。
     */
    override fun launch(): LaunchResult {
        val state = sharedBackend
        if (state is SharedBackendState.Unavailable) {
            Log.warn("拒绝启动官方桌面端：${state.reason}")
            return LaunchResult.Refused(state.reason)
        }
        val origin = (state as SharedBackendState.Ready).origin
        if (!probe(origin, PROBE_TIMEOUT_MS)) {
            val reason = "后端无响应（$origin），可能刚退出"
            Log.warn("拒绝启动官方桌面端：$reason")
            return LaunchResult.Refused(reason)
        }
        return runCatching {
            starter(exe, dataRoot, officialDesktopEnv(dataRoot, origin, sessionToken()))
            Log.info("已启动官方桌面端：${exe.absolutePath}（数据目录 $dataDir，共用后端 $origin）")
            LaunchResult.Started(origin)
        }.getOrElse { error ->
            Log.error("启动官方桌面端失败：${exe.absolutePath}", error)
            LaunchResult.Failed(error.message ?: error::class.java.name)
        }
    }

    companion object {
        /** 探活要快：点按钮的人最多等一两秒。 */
        const val PROBE_TIMEOUT_MS = 1_500L

        fun detect(
            config: AppConfig,
            paths: ConfigPaths,
            backendUrl: () -> String? = { null },
            sessionToken: () -> String? = { null },
            probe: (String, Long) -> Boolean = ::probeStatus,
            starter: (File, File, Map<String, String>) -> Unit = ::startDetached,
        ): OfficialDesktopLauncherImpl? {
            val configured = config.app.debug.officialDesktop?.takeIf { it.isNotBlank() }?.let { raw ->
                val file = File(raw)
                if (file.isAbsolute) file else File(paths.home, raw)
            }
            val auto = File(paths.hermesDir(config), "official-desktop${File.separator}Hermes.exe")

            val exe = listOfNotNull(configured, auto).firstOrNull { it.isFile } ?: return null
            return OfficialDesktopLauncherImpl(
                exe = exe,
                dataRoot = officialDesktopDataRoot(paths.home),
                backendUrl = backendUrl,
                sessionToken = sessionToken,
                probe = probe,
                starter = starter,
            )
        }
    }
}

/** 起进程并把输出落到对照实例自己的日志里。 */
internal fun startDetached(exe: File, dataRoot: File, env: Map<String, String>) {
    val log = File(dataRoot, "official-desktop.log").apply { parentFile?.mkdirs() }
    ProcessBuilder(exe.absolutePath)
        .directory(exe.parentFile)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        .apply { environment().putAll(env) }
        .start()
}

/** `GET /api/status` 是否 2xx。任何异常都当作「没响应」。 */
internal fun probeStatus(origin: String, timeoutMs: Long): Boolean = runCatching {
    val connection = URI("${origin.trimEnd('/')}/api/status").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = timeoutMs.toInt()
    connection.readTimeout = timeoutMs.toInt()
    try {
        connection.responseCode in 200..299
    } finally {
        connection.disconnect()
    }
}.getOrDefault(false)

/** 对照实例的数据根：`<home>/data/official-desktop`。 */
internal fun officialDesktopDataRoot(home: File): File = File(home, "data${File.separator}official-desktop")

/**
 * 对照实例必须带的环境变量（抽成纯函数便于单测）。
 *
 * `HERMES_DESKTOP_REMOTE_URL` 与 `HERMES_DESKTOP_REMOTE_TOKEN` **必须同时给**：
 * 官方桌面的 `resolveDesktopRemoteRoute()` 只看到 URL 而没 token 时会直接抛
 * `"HERMES_DESKTOP_REMOTE_URL is set but HERMES_DESKTOP_REMOTE_TOKEN is not"`。
 */
internal fun officialDesktopEnv(
    dataRoot: File,
    backendUrl: String? = null,
    sessionToken: String? = null,
): Map<String, String> = buildMap {
    put("HERMES_DESKTOP_USER_DATA_DIR", File(dataRoot, "userdata").absolutePath)
    put("HERMES_HOME", File(dataRoot, "hermes-home").absolutePath)
    val url = backendUrl?.trim().orEmpty()
    val token = sessionToken?.trim().orEmpty()
    if (isUsableBackendUrl(url) && token.isNotEmpty()) {
        put("HERMES_DESKTOP_REMOTE_URL", url)
        put("HERMES_DESKTOP_REMOTE_TOKEN", token)
    }
}

/**
 * 这个地址能不能交给官方桌面。
 *
 * 未就绪时后端给的是 `http://127.0.0.1:0`（故意的占位）—— 那种地址传过去只会让
 * 官方桌面立刻连不上，所以判据里直接排除 `:0`。
 */
internal fun isUsableBackendUrl(url: String): Boolean {
    val value = url.trim()
    if (!value.startsWith("http://") && !value.startsWith("https://")) return false
    val authority = value.substringAfter("://").substringBefore('/')
    val port = authority.substringAfterLast(':', missingDelimiterValue = "")
    return port.isNotEmpty() && port != "0" && port.all { it.isDigit() }
}