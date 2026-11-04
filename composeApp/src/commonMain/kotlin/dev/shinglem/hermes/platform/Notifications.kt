package dev.shinglem.hermes.platform

/**
 * 系统通知（Windows 桌面）。
 *
 * **只在窗口非前台 / 用户没看着那个会话时调用** —— 用户正看着界面时再弹一次是噪音，
 * 这个判断留在调用方（需要焦点与选中会话，common 侧拿不到）。
 *
 * 两条通路由平台实现决定：
 * - 带 [actions] 的（审批等需要就地决策的）→ `NotificationInbox` + Compose 自绘窗；
 * - 纯提示 → 系统托盘气泡（进通知中心，不打扰）。
 *
 * 平台没有托盘 / 托盘被策略禁用时 [supported] 为 false，[show] 变成空操作。
 */
expect object SystemNotifications {
    val supported: Boolean

    /**
     * @param actions 带按钮的动作（如审批的「允许一次 / 拒绝」）。有按钮的会在右下角弹出
     *   **可点击处理**的窗口，不需要先把主窗口拉出来。
     */
    fun show(title: String, body: String, actions: List<NotificationAction> = emptyList())

    /**
     * 点击通知（或托盘图标）时要执行的动作。
     *
     * 平台只能挂"最近一次"动作：审批 / 提问都等价于"跳到那个会话"，覆盖语义够用；
     * 传 null 表示点击时只把窗口拉到前台。
     */
    fun setPendingAction(action: (() -> Unit)?)

    /** 撤掉当前通知（请求已被处理 / 会话已切到前台）。 */
    fun dismiss()
}

/** 客户端自身版本号（`jpackage.app-version`；开发态为 `dev`）。 */
expect val clientVersion: String
