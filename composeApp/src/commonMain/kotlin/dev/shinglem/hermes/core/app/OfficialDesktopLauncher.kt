package dev.shinglem.hermes.core.app

/**
 * 官方 Electron 桌面端的启动器（debug-only，v1.0.0 移除；见 ADR-045）。
 *
 * 存在意义：同一个后端、两套 UI 并排对照调试。`available == false` 时设置页不出现
 * 「调试」入口 —— 不做置灰占位，避免"点了没反应"。
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
     */
    val dataDir: String

    /** 起一个新进程；返回 false 表示启动失败（页面显示原因由实现方写日志）。 */
    fun launch(): Boolean
}
