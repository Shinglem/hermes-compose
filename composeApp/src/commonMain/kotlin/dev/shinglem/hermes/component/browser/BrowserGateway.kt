package dev.shinglem.hermes.component.browser

import dev.shinglem.hermes.core.error.AppError

/**
 * 浏览器侧栏的窄接口（S2 模板：每个域一个）。
 *
 * 只暴露"这一页真正需要的三个调用"：
 * 1. Reducer/Executor 能在纯 JVM 里测（实现一个二十行的假实现，不用真浏览器）；
 * 2. 平台差异（`HWND` / `NativeView`）全部关在实现里，`commonMain` 不认识 Win32；
 * 3. 一眼能看出这一页**不该**做别的事 —— 特别是：**不该拉起或杀掉浏览器**（ADR-082）。
 */
interface BrowserGateway {

    /**
     * 找**所有** agent 正在用的浏览器（多实例）。
     *
     * 后端每个 launcher key 一只（不同会话 / profile 各一只），所以是列表。
     */
    suspend fun discoverAll(): DiscoveryResult

    /**
     * 当前活动页的 URL（用于"在系统浏览器打开"）。
     *
     * 拿不到返回 null —— 那是锦上添花的功能，失败不该影响面板本身。
     */
    suspend fun activePageUrl(port: Int): String?

    /**
     * [hwnd] 是否仍是 [pid] 的窗口（纳管后判活的**唯一**依据）。
     *
     * ⚠️ 判活**不要**用 [discoverAll]：`NativeView` 嵌入后窗口会变成 child window，
     * 发现只枚举顶层窗口，会返回"没找到" → 误判消失 → 销毁槽位 → 窗口恢复独立
     * → 下一轮再发现 → 无限跳变（实测踩过）。判活只问这两个 Win32 事实。
     *
     * 非 suspend：底层只是两个 Win32 调用，调用方不该为此开协程。
     */
    fun isSlotAlive(hwnd: Long, pid: Long): Boolean

    /**
     * 是否存在任何 launch record（**廉价**：只列目录，不读文件、不枚举窗口）。
     *
     * 给心跳做短路用：agent 从没起过浏览器时，每 5s 跑完整发现是浪费。
     */
    fun hasAnyRecord(): Boolean

    data class DiscoveryResult(
        /** 所有可纳管的实例（pid 唯一）。 */
        val targets: List<BrowserTarget> = emptyList(),
        /** 有进程但没有可见窗口（headless）；取第一个遇到的 pid。 */
        val headlessPid: Long? = null,
        val unsupported: Boolean = false,
    )

    /** 缺省实现：永远"没有浏览器"。非桌面平台 / 预览用。 */
    object Empty : BrowserGateway {
        override suspend fun discoverAll(): DiscoveryResult = DiscoveryResult(unsupported = true)
        override suspend fun activePageUrl(port: Int): String? = null
        override fun isSlotAlive(hwnd: Long, pid: Long): Boolean = false
        override fun hasAnyRecord(): Boolean = false
    }
}

/** Executor 产出的结果（`Reducer` 的输入）。 */
sealed interface BrowserResult {
    data object SearchStarted : BrowserResult

    /**
     * 一轮发现收尾（**无论有没有纳管成功**）。
     *
     * 存在的理由：`SearchStarted` 会把 phase 置成 `Searching`，而"已经有实例、这一轮没有新的"
     * 这条路径原本什么都不做，导致界面永远停在"正在查找…"的转圈上（实测踩过）。
     * 任何走进 `Searching` 的发现都必须以 `TakenOver` / `NotFound` / `Failed` /
     * `SearchFinished` 之一收尾。
     */
    data class SearchFinished(val found: Boolean) : BrowserResult

    /** 新纳管了若干只（追加到 `targets`）。 */
    data class TakenOver(val targets: List<BrowserTarget>) : BrowserResult

    /** 列表被整体替换（例如摘掉了已关闭的实例）。 */
    data class Replaced(val targets: List<BrowserTarget>) : BrowserResult

    /** 切换当前展示的那一只。 */
    data class Selected(val pid: Long) : BrowserResult

    data class NotFound(val headlessPid: Long?, val unsupported: Boolean) : BrowserResult

    data class Failed(val error: AppError) : BrowserResult

    data class PanelToggled(val open: Boolean) : BrowserResult

    data class PanelResized(val widthDp: Int) : BrowserResult

    /** 当前全部失效。 */
    data object Detached : BrowserResult

    /** 某一只的当前页 URL 已取到。 */
    data class PageUrl(val pid: Long, val url: String?) : BrowserResult

    data object ErrorDismissed : BrowserResult
}
