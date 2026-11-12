package dev.shinglem.hermes.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `config/config.json` 的类型化模型 —— **唯一的配置定义处**。
 *
 * 约定（见 `.design/详细设计/工程与代码/05-打包与配置.md` §3）：
 * - wire 名一律 `snake_case`（用 `@SerialName`），Kotlin 侧仍是 camelCase；
 *   这样「JSON 路径 ↔ `HDC_` 环境变量 ↔ `-Dhdc.*`」是零歧义的机械映射。
 * - **每个字段都必须有默认值**：缺失的键要能正常启动，`config.json` 只写要改的部分。
 * - 解析只负责形状，范围与枚举的语义校验在 [ConfigValidator]。
 */
@Serializable
data class AppConfig(
    val version: Int = CURRENT_VERSION,
    val backend: BackendSection = BackendSection(),
    val app: AppSection = AppSection(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackendSection(
    val mode: BackendMode = BackendMode.REMOTE,
    val local: LocalSection = LocalSection(),
    val remote: RemoteSection = RemoteSection(),
)

/** 后端来源。`LOCAL` 尚未实现（S7），但配置与校验先就位。 */
@Serializable
enum class BackendMode {
    @SerialName("local") LOCAL,
    @SerialName("remote") REMOTE,
}

/**
 * 启动时要不要管理员权限。
 *
 * - `never`：始终普通权限（默认）
 * - `ask`：未提权就用 `runas` 重启自己（**每次手动启动都会弹一次 UAC**，这是 Windows 规则）；
 *   用户点"否"则以普通权限继续运行，不阻塞启动
 *
 * 登录自启走计划任务（`task`），它本身已经是最高权限，不会再触发这里的分支。
 * 详见 `.design/详细设计/工程与代码/07-提权与自启动.md`。
 */
@Serializable
enum class ElevateMode {
    @SerialName("never") NEVER,
    @SerialName("ask") ASK,
}

/**
 * 开机自启的落地方式。
 *
 * - `off`：什么都不做
 * - `task`：维护一个登录触发的计划任务（最高权限、带 `--silent`）—— **唯一能做到
 *   "要管理员 + 登录不弹 UAC"的方式**，因为登录时由 Task Scheduler 服务创建进程，
 *   走不到 `consent.exe`
 *
 * 落地需要管理员权限（实测：非管理员连最低权限的计划任务都建不了），所以
 * `task` 通常要与 `elevate = ask` 一起用。任务名 = `HermesCompose-<home hash>`。
 */
@Serializable
enum class AutostartMode {
    @SerialName("off") OFF,
    @SerialName("task") TASK,
}

@Serializable
data class LocalSection(
    /** 内嵌 payload 目录，相对 `<home>`；也可给绝对路径。release 目录里就是 `hermes/`。 */
    @SerialName("hermes_dir") val hermesDir: String = "hermes",
    /**
     * 监听地址。
     *
     * 默认 `127.0.0.1` = 只接受本机连接（凭据是 session token）。
     * 填 `0.0.0.0` 会进入后端的 **gated 模式**：它要求配置登录口令 / OAuth，
     * 且本客户端只会发 session token —— 也就是说**别的机器上的桌面端连不上**，
     * 只有浏览器能过登录页。要给别人用，正确做法是保持回环 + 隧道（SSH `-L` / Tailscale）。
     */
    val host: String = DEFAULT_HOST,
    /**
     * 监听端口。`0` = 让系统分配（现状，避免与官方桌面端抢 9119）；
     * 固定一个值才能被隧道 / 脚本稳定指向。
     */
    val port: Int = 0,
    /**
     * 后端凭据。留空 = 每次启动随机生成（更安全，但也意味着别人无法预先配置）；
     * 填了就跨重启固定 —— 分享给别人的客户端配置 `backend.remote.token` 时用它。
     *
     * 注意它会以明文写进 `config.json`，等价于一把 API key。
     */
    val token: String? = null,
    /** 冷启动容忍。下限 45000 —— 低于它必然误判超时。 */
    @SerialName("ready_timeout_ms") val readyTimeoutMs: Int = 90_000,
    @SerialName("extra_args") val extraArgs: List<String> = emptyList(),
    /** 是否给子进程设 `HERMES_DESKTOP=1`（会让后端 tick 用户的 cron）。 */
    @SerialName("run_cron") val runCron: Boolean = true,
    /**
     * 数据根（`HERMES_HOME`），相对 `<home>`。
     *
     * 放在 `data/` 下而不是与 payload 平级：成品结构里 `hermes/` 是 payload、`data/` 是
     * 程序数据（见 `05-打包与配置.md` §1），同级的 `hermes-home/` 会让人误以为是 bundle。
     * 必须是本程序独占的目录：指向 `~/.hermes` 之类的既有 home 会被启动校验直接拒绝。
     */
    @SerialName("home_dir") val homeDir: String = "data/agent",
    /**
     * `local` 模式用哪条后端路线。
     *
     * [LocalEngine.GATEWAY]：`hermes gateway ensure` 拉起上游的**机器级 gateway** 再 attach
     * （0.23+ 的 canonical 路线；会话队列/history/steer 由 owner 统一保证）。
     * [LocalEngine.ISOLATED]：我们自己 spawn `dashboard --isolated`（旧路线，留作回退）。
     *
     * attach 之后 [host] / [port] / [extraArgs] 都不再生效 —— 端口由 daemon 决定，
     * 通过 endpoint 的 `api_origin` 告知。
     */
    @SerialName("engine") val engine: LocalEngine = LocalEngine.GATEWAY,
) {
    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"
    }
}

/** `local` 模式的两种后端路线，见 [LocalSection.engine]。 */
@Serializable
enum class LocalEngine {
    @SerialName("gateway") GATEWAY,
    @SerialName("isolated") ISOLATED,
}

@Serializable
data class RemoteSection(
    @SerialName("base_url") val baseUrl: String = "http://127.0.0.1:9119",
    val token: TokenConfig = TokenConfig(),
    @SerialName("connect_timeout_ms") val connectTimeoutMs: Int = 10_000,
)

/** 凭据来源：环境变量 / dotenv 文件 / 内联字符串。 */
@Serializable
data class TokenConfig(
    val source: TokenSource = TokenSource.ENV_FILE,
    val value: String? = null,
)

@Serializable
enum class TokenSource {
    @SerialName("env") ENV,
    @SerialName("env_file") ENV_FILE,
    @SerialName("inline") INLINE,
}

@Serializable
data class AppSection(
    /** 强制 profile；`null` = 跟随 `state.json` 里上次选中的。 */
    val profile: String? = null,
    /** 数据根；`null` = `%LOCALAPPDATA%\hermes-compose`，便携模式填 `"data"`（相对 `<home>`）。 */
    @SerialName("data_dir") val dataDir: String? = null,
    /** `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`（大小写不敏感）。 */
    @SerialName("log_level") val logLevel: String = "INFO",
    val log: LogSection = LogSection(),
    val window: WindowSection = WindowSection(),
    /** 关闭窗口时收到托盘而不是退出（托盘菜单里才有「退出」）。 */
    @SerialName("close_to_tray") val closeToTray: Boolean = true,
    /** 启动时是否要求管理员权限（`ask` = 弹一次 UAC 提权重启自己）。 */
    val elevate: ElevateMode = ElevateMode.NEVER,
    /** 开机自启方式；`task` = 登录触发的计划任务（最高权限、不弹 UAC，需要管理员）。 */
    val autostart: AutostartMode = AutostartMode.OFF,
    /** 调试用（v0.2.0~，v1.0.0 移除）；见 ADR-045。 */
    val debug: DebugSection = DebugSection(),
    /** 内置后端的浏览器行为。 */
    val browser: BrowserSection = BrowserSection(),
)

/**
 * 浏览器工具的行为（对应后端的 `browser.*` 配置，但用环境变量注入 —— 见 [headed]）。
 *
 * 为什么不直接改后端 `config.yaml`：`PUT /api/config/raw` 是**整篇 YAML 替换**
 * （`web_routers/analytics.py` 明确写了 "never merge omitted sections back from disk"），
 * 写坏就毁掉用户整份后端配置。环境变量是进程级的，出问题只影响本次启动。
 */
@Serializable
data class BrowserSection(
    /**
     * 让 agent 操作的浏览器**可见**（`AGENT_BROWSER_HEADED=1`）。
     *
     * 打开后用户能看到 agent 在访问什么页面、点了哪里；代价是每轮不再自动清理浏览器窗口
     * （空闲超时仍会回收）。**改完需要重启后端才生效**（环境变量是启动时注入的）。
     */
    val headed: Boolean = false,
    /**
     * 追加的 Chromium 启动参数（`AGENT_BROWSER_ARGS`，逗号分隔）。
     *
     * 留空即只走自动判断：**以管理员运行时**自动补
     * `--do-not-de-elevate,--no-sandbox,--disable-dev-shm-usage`。
     *
     * `--do-not-de-elevate` 是关键那一条：管理员令牌下 Chromium 会「自动去提权」——
     * 主进程取 `explorer.exe` 的令牌拉起一个中完整性副本后自己 `exit 0`，而后端
     * `_wait_for_devtools()` 先读端口文件再 `poll()` 判生死，父进程先死就误报
     * `Chrome exited early (exit code: 0) without writing DevToolsActivePort`。
     * 实测只加 `--no-sandbox` 仍然 `exit 0`，加了这个开关才稳。
     * 后端 `_needs_chromium_sandbox_bypass()` 只判 Linux root / Docker / AppArmor，Windows 恒 false。
     *
     * ⚠️ 后端 config 版本 52 之后不再读这个环境变量（只在 51→52 迁移里用过一次）。
     * 管理员模式的真正修复由 `BrowserArgsReconciler` 写进后端 `browser.chromium_args` 完成；
     * 这里保留是为了兼容更老的后端。
     *
     * 这个字段与自动部分**合并**（去重），所以填 `--remote-debugging-port=9333` 不会丢掉自动段。
     */
    val args: String = "",
)

/**
 * 调试knobs —— 只在 v0.2.0~v0.x 期间存在，v1.0.0 随官方对照包一起移除。
 */
@Serializable
data class DebugSection(
    /**
     * 官方 Electron 桌面端 `Hermes.exe` 的路径。
     *
     * `null` = 自动探测 payload 下的 `official-desktop/Hermes.exe`；显式给了路径但不存在时，
     * 设置页的「调试」入口不出现（不做置灰占位）。
     */
    @SerialName("official_desktop") val officialDesktop: String? = null,
)

@Serializable
data class LogSection(
    /** 相对 `<home>`。 */
    val dir: String = "logs",
    @SerialName("keep_days") val keepDays: Int = 14,
    @SerialName("max_files") val maxFiles: Int = 20,
    @SerialName("also_stderr") val alsoStderr: Boolean = true,
)

@Serializable
data class WindowSection(
    val width: Int = 1440,
    val height: Int = 900,
    @SerialName("min_width") val minWidth: Int = 900,
    @SerialName("min_height") val minHeight: Int = 600,
)
