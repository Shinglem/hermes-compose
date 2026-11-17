package dev.shinglem.hermes.component.browser

/** 浏览器侧栏的用户意图。 */
sealed interface BrowserIntent {
    /** 展开 / 收起抽屉。收起时**不去发现**（省一轮 IO），但保留已知的 target。 */
    data object TogglePanel : BrowserIntent

    /** 拖拽改变宽度（dp）。UI 层已夹到 `[MIN_WIDTH_DP, MAX_WIDTH_DP]`。 */
    data class ResizePanel(val widthDp: Int) : BrowserIntent

    /** 手动重新发现（覆盖「暂停」状态的下一次定时发现）。 */
    data object Refresh : BrowserIntent

    /**
     * 定时发现的心跳（由 UI 在面板展开期间每 5s 发一次）。
     *
     * 与 [Refresh] 分开是因为两者的副作用不同：`Refresh` 会先切到 `Searching`
     * （用户看得见"正在找"），心跳不该让 UI 闪一下。
     */
    data object Heartbeat : BrowserIntent

    /** 把当前页丢给系统浏览器打开。 */
    data object OpenInSystemBrowser : BrowserIntent

    /** 槽位报告失效（窗口没了）。 */
    data object SlotLost : BrowserIntent

    /**
     * agent 开始操作浏览器：**纳管**它（把窗口收进本进程），但**不打开抽屉**。
     *
     * 判据是 `tool.start` 的工具名以 `browser` 开头（后端那批工具都叫
     * `browser_navigate` / `browser_click` / `browser_exec` …）。
     *
     * 为什么不在启动时纳管：那时浏览器通常还不存在；也不该因为"进程里有个浏览器"
     * 就把它抢过来（可能是别的程序留下的）。
     */
    data object TakeOver : BrowserIntent

    /** 切换当前展示的是哪一只（多实例时用）。 */
    data class SelectTarget(val pid: Long) : BrowserIntent

    /** 关掉错误提示条。 */
    data object DismissError : BrowserIntent
}
