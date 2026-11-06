package dev.shinglem.hermes.core.app

/**
 * 官方 Electron 桌面端的启动器（debug-only，v1.0.0 移除；见 ADR-045）。
 *
 * 存在意义：同一个后端、两套 UI 并排对照调试。`detect` 返回 null 时设置页不出现
 * 「集成 → 官方桌面端」入口 —— 不做置灰占位，避免"点了没反应"。
 *
 * **共用后端是硬要求**：官方桌面端被我们用 `HERMES_DESKTOP_REMOTE_URL` +
 * `HERMES_DESKTOP_REMOTE_TOKEN` 指向本客户端当前的后端，因此它不会再起一个自己的 daemon。
 * 那两个环境变量必须**同时**给出（只给 URL 上游会直接抛错），任一不可用就拒绝启动 ——
 * 否则它会走自己的本地路径，在同一台机器上留下第二套后端、第二套会话。
 *
 * commonMain 只声明行为，找路径与起进程都在 desktop 侧（`OfficialDesktop.desktop.kt`）。
 */
interface OfficialDesktopLauncher {
    /** `Hermes.exe` 的绝对路径（给页面展示）。 */
    val path: String

    /**
     * 对照实例自己的数据根（`userData` 与 `HERMES_HOME` 都在它下面）。
     *
     * 必须与用户本机官方客户端的默认位置分开：官方客户端用 `requestSingleInstanceLock()`
     * 按 userData 判重，共用 userData 时新实例会**静默退出**并把已有窗口带到前台。
     * 注意这**不影响**共用后端 —— 后端由我们的 `HERMES_HOME` 决定，与它这份隔离的本地配置无关。
     */
    val dataDir: String

    /** 当前能否共用后端；不可用时给出可读原因（页面直接显示）。惰性求值。 */
    val sharedBackend: SharedBackendState

    /** 起一个新进程（或说明为什么不起）。 */
    fun launch(): LaunchResult
}

/** 官方桌面端能否复用本客户端当前的后端。 */
sealed interface SharedBackendState {

    /** 后端已就绪，官方桌面端会连到 [origin]。 */
    data class Ready(val origin: String) : SharedBackendState

    /** 不可用；[reason] 是给人看的一句话（例如「后端还在启动」）。 */
    data class Unavailable(val reason: String) : SharedBackendState
}

/** 一次启动尝试的结果。 */
sealed interface LaunchResult {

    /** 已拉起，并且会连到 [origin] 这一个后端。 */
    data class Started(val origin: String) : LaunchResult

    /** 主动拒绝（后端没就绪 / 探活失败），没有启动任何进程。 */
    data class Refused(val reason: String) : LaunchResult

    /** 进程创建失败（路径不对、权限等）。 */
    data class Failed(val reason: String) : LaunchResult
}