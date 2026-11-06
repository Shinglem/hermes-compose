package dev.shinglem.hermes.platform

/**
 * 主窗口是否在前台。
 *
 * 通知只在"用户没看着"的时候发：正盯着界面时再弹一条系统提示是噪音。
 * common 侧拿不到窗口焦点，只能由平台层回答。
 *
 * [update] 是**权威来源**：Compose 的 `LocalWindowInfo.isWindowFocused` 只盯着主窗口，
 * 由界面层在每次变化时推下来；平台层自己推断（AWT）只在没收到推送前兜底 ——
 * 推断版会把 tooltip / 弹层这类辅助窗口的进程内焦点当成"用户在看"，实测吞掉过提醒。
 */
expect object AppFocus {
    val isForeground: Boolean

    /** 由界面层推送主窗口的焦点状态（Compose `LocalWindowInfo.isWindowFocused`）。 */
    fun update(foreground: Boolean)
}
