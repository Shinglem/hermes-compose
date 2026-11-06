package dev.shinglem.hermes.platform

import dev.shinglem.hermes.core.log.Log
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference

/**
 * `AppFocus` 的桌面实现。
 *
 * 权威值是 [update] 推下来的（Compose 的 `LocalWindowInfo.isWindowFocused`，只盯主窗口）。
 *
 * [isForeground] 在未收到推送前用 AWT 兜底，但**不能用 `isFocused`**：它是「进程内焦点」，
 * 而 `Window.getWindows()` 里除主窗口外还有 tooltip / 弹层等辅助窗口，任意一个持有进程内焦点
 * 都会让 `any { isFocused }` 为真 —— 于是「人在别的应用里」被判成「正在看我」，
 * 审批提醒被 `shouldRemindForSession` 静默跳过（实测日志：三行
 * `remind skipped … (foreground and viewing this session)`，3 个审批只弹出 1 个）。
 *
 * 兜底改用 `isActive`（AWT 语义 = 系统级活动窗口），且只认 [Frame] / [Dialog]。
 */
actual object AppFocus {
    private val pushed = AtomicReference<Boolean?>(null)
    private var probed = false

    actual val isForeground: Boolean
        get() = pushed.get() ?: awtGuess()

    actual fun update(foreground: Boolean) {
        val previous = pushed.getAndSet(foreground)
        if (previous != foreground) Log.info("focus pushed: foreground=$foreground")
    }

    private fun awtGuess(): Boolean = runCatching {
        val windows = Window.getWindows()
        if (!probed) {
            probed = true
            // 只打一次：窗口层级里到底有谁（tooltip / 弹层是误判来源）。
            Log.info(
                "focus probe: " + windows.filter { it.isVisible }.joinToString(", ") { w ->
                    "${w.javaClass.simpleName}(active=${w.isActive} focused=${w.isFocused})"
                },
            )
        }
        windows.any { window ->
            (window is Frame || window is Dialog) && window.isVisible && window.isActive
        }
    }.getOrDefault(true)
}
