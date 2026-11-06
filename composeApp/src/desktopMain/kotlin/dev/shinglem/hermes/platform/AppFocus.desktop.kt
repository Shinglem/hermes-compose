package dev.shinglem.hermes.platform

import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import dev.shinglem.hermes.core.log.Log

/**
 * `AppFocus.isForeground` 的桌面实现。
 *
 * **不能用 `isFocused`**：它是「进程内焦点」，而 `Window.getWindows()` 里除主窗口外还有
 * tooltip / 弹层等辅助窗口，其中任意一个持有进程内焦点都会让 `any { isFocused }` 为真 ——
 * 于是「人在别的应用里」被误判成「正在看我」，审批提醒被 `shouldRemindForSession` 静默跳过。
 * （实测：3 个审批只弹 1 个，日志三行 `remind skipped … (foreground and viewing this session)`。）
 *
 * `isActive` 的语义是「当前系统级活动窗口」，且只有 [Frame] / [Dialog] 可能为真 ——
 * 与「用户此刻在看谁」一致。
 */
actual object AppFocus {
    private var dumped = false

    actual val isForeground: Boolean
        get() = runCatching {
            val windows = Window.getWindows()
            if (!dumped) {
                dumped = true
                // 只打一次：确认 isFocused / isActive 到底差在哪（tooltip、弹层这类辅助窗口是元凶）。
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