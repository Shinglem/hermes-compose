package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.ConfigPaths
import dev.shinglem.hermes.core.log.Log
import java.io.File

/**
 * 官方 Electron 桌面端（debug-only，v1.0.0 移除；见 ADR-045）。
 *
 * 查找顺序：
 * 1. `config.json` 的 `app.debug.official_desktop`（绝对路径，或相对 `<home>`）
 * 2. payload 下的 `official-desktop/Hermes.exe`（CI 打进来的对照包）
 *
 * 两处都没有 → [detect] 返回 null，设置页不出现「调试」入口。
 */
class OfficialDesktopLauncherImpl(
    private val exe: File,
    private val dataRoot: File,
    /** 后端地址取值器。**延迟求值**：LOCAL 模式要等后端 Ready 后端口才确定。 */
    private val backendUrl: () -> String?,
    /** 后端会话令牌取值器；与后端子进程收到的 `HERMES_DASHBOARD_SESSION_TOKEN` 是同一个。 */
    private val sessionToken: () -> String?,
) : OfficialDesktopLauncher {

    override val path: String get() = exe.absolutePath

    override val dataDir: String get() = dataRoot.absolutePath

    /**
     * 起一个独立进程。
     *
     * 四件事都是实测出来的，缺一件对照包就起不来、进向导或拖垮本机官方客户端：
     * - `HERMES_DESKTOP_USER_DATA_DIR`：官方客户端按 userData 抢单实例锁，共用时新实例
     *   打印 `another Hermes Desktop instance holds the single-instance lock; exiting` 后 exit 0；
     * - `HERMES_HOME`：不隔离就会复用用户本机 home 并拉起生产后端，污染真实数据；
     * - `HERMES_DESKTOP_REMOTE_URL` + `HERMES_DESKTOP_REMOTE_TOKEN`：不给就会走"自己拉起后端"
     *   的本地路径，于是停在"配置后端"向导里（两个必须同时给，只给 URL 会直接抛错）；
     * - 输出重定向到文件：`ProcessBuilder` 的管道没人读，写满 64 KiB 后子进程会卡住。
     */
    override fun launch(): Boolean = runCatching {
        val log = File(dataRoot, "official-desktop.log").apply { parentFile?.mkdirs() }
        val url = backendUrl()
        val token = sessionToken()
        ProcessBuilder(exe.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply { environment().putAll(officialDesktopEnv(dataRoot, url, token)) }
            .start()
        Log.info("已启动官方桌面端：${exe.absolutePath}（数据目录 $dataDir，后端 ${url ?: "未就绪"}）")
        true
    }.getOrElse { error ->
        Log.error("启动官方桌面端失败：${exe.absolutePath}", error)
        false
    }

    companion object {
        fun detect(
            config: AppConfig,
            paths: ConfigPaths,
            backendUrl: () -> String? = { null },
            sessionToken: () -> String? = { null },
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
            )
        }
    }
}

/** 对照实例的数据根：`<home>/data/official-desktop`。 */
internal fun officialDesktopDataRoot(home: File): File = File(home, "data${File.separator}official-desktop")

/**
 * 对照实例必须带的环境变量（抽成纯函数便于单测）。
 *
 * `HERMES_DESKTOP_REMOTE_URL` 与 `HERMES_DESKTOP_REMOTE_TOKEN` **必须同时给**：
 * 官方桌面的 `resolveDesktopRemoteRoute()` 只看到 URL 而没 token 时会直接抛
 * `"HERMES_DESKTOP_REMOTE_URL is set but HERMES_DESKTOP_REMOTE_TOKEN is not"`。
 * 两者任一不可用就都不设 —— 宁可让它进配置向导，也不要让它启动即报错。
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
 * `LocalBackendProvider` 在未 Ready 时给的是 `http://127.0.0.1:0`（故意的占位，
 * 见 `currentTarget()` 的注释）—— 那种地址传过去只会让官方桌面立刻连不上，
 * 不如不传、让它走自己的配置路径。
 */
internal fun isUsableBackendUrl(url: String): Boolean {
    val value = url.trim()
    if (!value.startsWith("http://") && !value.startsWith("https://")) return false
    val authority = value.substringAfter("://").substringBefore('/')
    val port = authority.substringAfterLast(':', missingDelimiterValue = "")
    return port.isNotEmpty() && port != "0" && port.all { it.isDigit() }
}
