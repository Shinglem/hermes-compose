package dev.shinglem.hermes.platform

import dev.shinglem.hermes.core.log.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * `AppFocus` 的桌面实现。
 *
 * 权威值是 [update] 推下来的（Compose 的 `LocalWindowInfo.isWindowFocused`，
 * 由 `AppShell` 在 `LaunchedEffect` 里推送）。
 *
 * ### 兜底为什么不再用 AWT（S6 收尾）
 *
 * 旧实现兜底走 `java.awt.Window.getWindows()`，而 S6 迁 Tao 之后进程里**没有 AWT 窗口**
 * （主窗口是 Tao 的原生窗口，不是 `java.awt.Frame`/`Dialog`），`Window.getWindows()`
 * 恒为空 → `any { ... }` 恒为 false。
 *
 * 更糟的是它的失败方向：`isForeground = false` 意味着"用户没在看"，于是**每条**通知都会弹 ——
 * 而它本该是"没收到推送时才用的保守猜测"。S6 之后这个兜底等于恒保守，
 * 提醒会变成噪音（旧代码实测踩过的反面是恒 true 导致提醒被吞掉，现在是反过来）。
 *
 * 现在改为查 **Nucleus 的窗口焦点状态**（`NucleusWindow.isFocused`），与主窗口是同一套
 * 窗口模型。拿不到窗口句柄时退回"假定在前台"（保守但不会制造噪音）。
 *
 * ### 为什么不能用 `isFocused` 的进程内语义来判 `Frame`/`Dialog`
 *
 * 迁移前的注释保留在这里作为历史：`Window.getWindows()` 里除主窗口外还有 tooltip / 弹层，
 * 任意一个持有进程内焦点都会让 `any { isFocused }` 为真 —— 于是"人在别的应用里"
 * 被判成"正在看我"，审批提醒被静默跳过（实测日志：三行
 * `remind skipped … (foreground and viewing this session)`，3 个审批只弹出 1 个）。
 * 新实现只有一个窗口，这个问题不存在。
 */
actual object AppFocus {
    private val pushed = AtomicReference<Boolean?>(null)

    /** 由 `Main` 注入：返回主窗口当前是否持有系统级焦点。 */
    @Volatile
    private var probe: (() -> Boolean)? = null

    actual val isForeground: Boolean
        get() = pushed.get() ?: probe?.let { runCatching(it).getOrDefault(true) } ?: true

    actual fun update(foreground: Boolean) {
        val previous = pushed.getAndSet(foreground)
        if (previous != foreground) Log.info("focus pushed: foreground=$foreground")
    }

    /**
     * 注册主窗口的焦点查询（S6：从 Nucleus 的 `NucleusWindow.isFocused` 来）。
     *
     * 传 `null` 表示回到"假定在前台"。
     */
    fun attachProbe(block: (() -> Boolean)?) {
        probe = block
    }

    /** 清掉推送值，让 [isForeground] 重新走 [probe]（测试与窗口重建时用）。 */
    fun clearPushed() {
        pushed.set(null)
    }
}