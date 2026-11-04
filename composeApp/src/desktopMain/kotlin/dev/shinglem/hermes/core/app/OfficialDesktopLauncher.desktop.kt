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
) : OfficialDesktopLauncher {

    override val path: String get() = exe.absolutePath

    override val dataDir: String get() = dataRoot.absolutePath

    /**
     * 起一个独立进程。
     *
     * 三件事都是实测出来的，缺一件对照包就起不来或拖垮本机官方客户端：
     * - `HERMES_DESKTOP_USER_DATA_DIR`：官方客户端按 userData 抢单实例锁，共用时新实例
     *   打印 `another Hermes Desktop instance holds the single-instance lock; exiting` 后 exit 0；
     * - `HERMES_HOME`：不隔离就会复用用户本机 home 并拉起生产后端，污染真实数据；
     * - 输出重定向到文件：`ProcessBuilder` 的管道没人读，写满 64 KiB 后子进程会卡住。
     */
    override fun launch(): Boolean = runCatching {
        val log = File(dataRoot, "official-desktop.log").apply { parentFile?.mkdirs() }
        ProcessBuilder(exe.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply { environment().putAll(officialDesktopEnv(dataRoot)) }
            .start()
        Log.info("已启动官方桌面端：${exe.absolutePath}（数据目录 $dataDir）")
        true
    }.getOrElse { error ->
        Log.error("启动官方桌面端失败：${exe.absolutePath}", error)
        false
    }

    companion object {
        fun detect(config: AppConfig, paths: ConfigPaths): OfficialDesktopLauncherImpl? {
            val configured = config.app.debug.officialDesktop?.takeIf { it.isNotBlank() }?.let { raw ->
                val file = File(raw)
                if (file.isAbsolute) file else File(paths.home, raw)
            }
            val auto = File(paths.hermesDir(config), "official-desktop${File.separator}Hermes.exe")

            val exe = listOfNotNull(configured, auto).firstOrNull { it.isFile } ?: return null
            return OfficialDesktopLauncherImpl(exe, officialDesktopDataRoot(paths.home))
        }
    }
}

/** 对照实例的数据根：`<home>/data/official-desktop`。 */
internal fun officialDesktopDataRoot(home: File): File = File(home, "data${File.separator}official-desktop")

/** 对照实例必须带的环境变量（抽成纯函数便于单测）。 */
internal fun officialDesktopEnv(dataRoot: File): Map<String, String> = mapOf(
    "HERMES_DESKTOP_USER_DATA_DIR" to File(dataRoot, "userdata").absolutePath,
    "HERMES_HOME" to File(dataRoot, "hermes-home").absolutePath,
)
