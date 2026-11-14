package dev.shinglem.hermes.platform

import dev.shinglem.hermes.shell.notification.NativeNotifications


/**
 * 桌面通知。
 *
 * 两条通路：
 * - **带按钮**（审批等需要就地决策的）→ `NativeNotifications` 的原生 toast；
 *   发不出去就投递到 [NotificationInbox]，由 Compose 的 `NotificationOverlay`
 *   （右下角 M3 卡片）渲染，点了就地处理；
 * - **纯提示**（一轮完成等）→ 同样先试原生 toast，**失败时回退到 [NotificationInbox]**
 *   （自动收起的卡片），绝不静默丢弃。
 *
 * 为什么不走 WinRT Toast 的按钮：需要 AUMID + 开始菜单快捷方式 + 激活协议/COM 服务器，
 * 绿色版每次移动目录都得重新对账，见 `.design/详细设计/工程与代码/12-通知窗.md`。
 *
 * ### 历史（S6 遗留的真实缺陷，已修）
 *
 * 旧实现里纯提示走 `TrayIcon.displayMessage`，而那个 `TrayIcon` 由 `DesktopTray.install()`
 * 注入。S6 把托盘换成 `NucleusTray` 之后 `DesktopTray` **再没有被实例化**，
 * 于是 `icon` 恒为 null —— `TurnFinished` 这类无按钮通知在 `val target = icon ?: return`
 * 处**静默返回**，用户什么都不会看到。现在改为「原生 toast → 应用内卡片」两级回退，
 * 不再依赖托盘图标。
 */
actual object SystemNotifications {
    /** 托盘图标 / 通知气泡被点击时要执行的动作（`null` = 只把窗口拉到前台）。 */
    @Volatile
    private var pendingAction: (() -> Unit)? = null

    /** 没有挂起动作时的兜底（把窗口拉到前台），由 `Main` 注入。 */
    @Volatile
    private var fallbackActivate: (() -> Unit)? = null

    /**
     * 通知功能是否可用。
     *
     * **不再看 `SystemTray.isSupported()`**：托盘气泡那条通路已经废弃（见类注释），
     * 现在的最低保障是应用内自绘卡片（Compose 渲染），它永远可用。
     * 用托盘支持与否来判断，会在"任务栏没有通知区"的环境中把通知整个关掉 ——
     * 而那种环境下自绘卡片其实还能正常工作。
     */
    actual val supported: Boolean
        get() = true

    actual fun show(title: String, body: String, actions: List<NotificationAction>) {
        // 点标题 / 正文：有挂起动作就跳过去，否则把主窗口拉到前台。
        val onBodyClick: () -> Unit = {
            if (!consumePendingAction()) fallbackActivate?.let { runCatching(it) }
        }
        if (actions.isNotEmpty()) {
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
        // 纯提示：同样先试原生 toast，失败就投递一张会自动收起的应用内卡片。
        if (NativeNotifications.show(title, body, emptyList(), onBodyClick)) return
        NotificationInbox.post(
            title = title,
            body = body,
            actions = emptyList(),
            onBodyClick = onBodyClick,
            autoDismissMs = 6_000,
        )
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

    /**
     * 由入口注册"没有挂起动作时把窗口拉到前台"的兜底。
     *
     * 旧签名收 `TrayIcon`（用于 `displayMessage` 发纯提示）。现在纯提示不再依赖托盘图标，
     * 所以只剩"点通知时把窗口拉出来"这一件事。
     */
    fun attachActivation(onActivate: (() -> Unit)?) {
        fallbackActivate = onActivate
    }
}
