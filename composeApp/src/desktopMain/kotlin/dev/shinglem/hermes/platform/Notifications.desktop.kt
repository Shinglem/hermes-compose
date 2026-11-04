package dev.shinglem.hermes.platform

import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * 桌面通知。
 *
 * 两条通路：
 * - **带按钮**（审批等需要就地决策的）→ 投递到 [NotificationInbox]，由 Compose 的
 *   `NotificationOverlay`（右下角 M3 卡片）渲染，点了就地处理；
 * - **纯提示**（一轮完成等）→ 复用主窗口那份 [TrayIcon] 的 `displayMessage`，
 *   进系统通知中心，不打扰。
 *
 * 为什么不走 WinRT Toast 的按钮：需要 AUMID + 开始菜单快捷方式 + 激活协议/COM 服务器，
 * 绿色版每次移动目录都得重新对账，见 `.design/详细设计/工程与代码/12-通知窗.md`。
 *
 * 为什么不多建一个托盘图标：通知区里出现两个 Hermes 图标比没有通知更糟。
 * 图标由 `DesktopTray.install()` 注册进来。
 */
actual object SystemNotifications {
    @Volatile
    private var icon: TrayIcon? = null

    /** 托盘图标 / 通知气泡被点击时要执行的动作（`null` = 只把窗口拉到前台）。 */
    @Volatile
    private var pendingAction: (() -> Unit)? = null

    /** 没有挂起动作时的兜底（把窗口拉到前台），由 `DesktopTray` 注入。 */
    @Volatile
    private var fallbackActivate: (() -> Unit)? = null

    actual val supported: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    actual fun show(title: String, body: String, actions: List<NotificationAction>) {
        if (actions.isNotEmpty()) {
            // 点标题 / 正文：有挂起动作就跳过去，否则把主窗口拉到前台。
            val onBodyClick: () -> Unit = {
                if (!consumePendingAction()) fallbackActivate?.let { runCatching(it) }
            }
            // 原生 toast 优先（用户开了开关、且开始菜单快捷方式在）；发不出去就回退自绘窗 ——
            // 不能因为快捷方式被删就变成"没有通知"。
            if (NativeNotifications.show(title, body, actions, onBodyClick)) return
            NotificationInbox.post(
                title = title,
                body = body,
                actions = actions,
                onBodyClick = onBodyClick,
            )
            return
        }
        val target = icon ?: return
        runCatching {
            // 纯提示走系统气泡：进通知中心，不打扰，也不会挡屏幕。
            target.displayMessage(title, body.take(240), TrayIcon.MessageType.NONE)
        }
    }

    actual fun setPendingAction(action: (() -> Unit)?) {
        pendingAction = action
    }

    actual fun dismiss() {
        NotificationInbox.dismissAll()
    }

    /**
     * 托盘图标 / 通知气泡被点击时调用。
     *
     * @return true 表示有动作被消费（调用方不必再执行默认的"拉出窗口"）。
     */
    fun consumePendingAction(): Boolean {
        val action = pendingAction ?: return false
        pendingAction = null
        runCatching { action() }
        return true
    }

    /** 由 `DesktopTray` 在安装/卸载时调用。 */
    fun attach(trayIcon: TrayIcon?, onActivate: (() -> Unit)? = null) {
        icon = trayIcon
        fallbackActivate = onActivate
        if (trayIcon == null) {
            pendingAction = null
            fallbackActivate = null
        }
    }
}
