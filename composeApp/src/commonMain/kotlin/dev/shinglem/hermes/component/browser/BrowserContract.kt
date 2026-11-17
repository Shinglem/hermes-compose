package dev.shinglem.hermes.component.browser

import androidx.compose.runtime.Immutable

/**
 * 浏览器侧栏的状态（v0.6.1，ADR-082）。
 *
 * ### 两条正交的轴
 *
 * - **纳管**由 [phase] 表达（`TakenOver` = 至少有一只窗口已收进本进程）；
 * - **展示**由 [panelOpen] 表达（抽屉开着没有）。
 *
 * 两者**独立**：agent 调用浏览器工具时纳管但不打开抽屉；用户点侧栏才打开。
 *
 * ### 多实例
 *
 * 后端每个 launcher key 一只浏览器（不同会话 / 不同 profile 各一只），所以 [targets]
 * 是**列表**。抽屉按列表顺序展示，[activePid] 标记当前看哪一只。
 *
 * **为什么 `Phase` 里要区分 `NoBrowser` / `Headless` / `Unsupported`**：三种"没有画面"
 * 的原因完全不同，用户能做的动作也不同 ——
 * - `NoBrowser`：agent 还没访问过网页 → 让它访问一个；
 * - `Headless`：agent 浏览器在 headless 模式 → 改后端 `browser.headed`；
 * - `Unsupported`：非 Windows → 这个功能在本平台没有。
 */
@Immutable
data class BrowserState(
    /** 抽屉是否展开（**只**由用户操作改变）。 */
    val panelOpen: Boolean = false,
    /** 抽屉宽度（dp）。超出可用宽度的由 UI 层夹取。 */
    val panelWidthDp: Int = DEFAULT_WIDTH_DP,
    /** 已纳管的浏览器（按纳管顺序）。空 = 还没纳管。 */
    val targets: List<BrowserTarget> = emptyList(),
    /** 当前展示哪一只（`targets` 里的 pid）；`null` = 用第一只。 */
    val activePid: Long? = null,
    val phase: Phase = Phase.Idle,
    val lastError: String? = null,
) {
    /** 是否至少纳管了一只（与是否展示无关）。 */
    val takenOver: Boolean get() = phase is Phase.TakenOver

    /** 当前展示的那一只；`activePid` 不在列表里时退回第一只。 */
    val activeTarget: BrowserTarget?
        get() = targets.firstOrNull { it.pid == activePid } ?: targets.firstOrNull()

    /** 当前是否有画面可显示（纳管 **且** 抽屉开着）。 */
    val showing: Boolean get() = takenOver && panelOpen

    companion object {
        const val DEFAULT_WIDTH_DP = 360
        const val MIN_WIDTH_DP = 240
        const val MAX_WIDTH_DP = 900
    }
}

/** 纳管状态（窗口层面的进展）。 */
sealed interface Phase {
    /** 还没纳管，也没在找。 */
    data object Idle : Phase

    /** 正在发现。 */
    data object Searching : Phase

    /** 没有活着的 agent 浏览器。 */
    data object NoBrowser : Phase

    /** 有活着的进程，但没有可见窗口（headless 模式）。 */
    data class Headless(val pid: Long) : Phase

    /**
     * 已纳管：至少一只窗口已收进本进程。
     *
     * **注意**：进入这一态**不代表**抽屉打开 —— 见 [BrowserState.panelOpen]。
     */
    data object TakenOver : Phase

    /** 发现或挂载失败。 */
    data class Failed(val reason: String) : Phase

    /** 平台不支持（非 Windows）。 */
    data object Unsupported : Phase
}

/** 找到的 agent 浏览器（component 层视角，不含平台细节）。 */
@Immutable
data class BrowserTarget(
    val pid: Long,
    val hwnd: Long,
    val port: Int,
    /** 当前活动页的 URL（取到时填）；`null` = 还没取或取不到。 */
    val pageUrl: String? = null,
)

/** 一次性事件（不重放）。 */
sealed interface BrowserEffect {
    /** 把 [url] 交给系统默认浏览器打开。 */
    data class OpenExternal(val url: String) : BrowserEffect
}
