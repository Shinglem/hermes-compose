package dev.shinglem.hermes.shell.tray

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.nucleusframework.composenativetray.tray.api.Tray

/**
 * 托盘（ADR-062 / ADR-066 的配套）。
 *
 * 替代 v0.5.x 的 `core/app/DesktopTray`（Swing `SystemTray` + `JPopupMenu`）。
 *
 * **为什么必须换**：Tao 进程没有 AWT 事件循环（实测：`AWT-Windows` 线程在，但没有
 * `AWT-EventQueue`），Swing 菜单会走 AWT 的窗口管理并与 Tao 的窗口栈冲突。
 * `composenativetray` 用原生桥（`WindowsNativeBridge.nativeCreateTray`），顺带解决了
 * CMP-1936 —— 旧实现里 AWT `PopupMenu` 按 ANSI 转码导致中文菜单变方框。
 *
 * **保持的对外语义**（与旧 `DesktopTray` 一致，不趁机加功能）：
 * - 左键单击（`primaryAction`）与菜单「显示主窗口」都只是**请求显示**，不直接操作窗口；
 * - 菜单只有三项：显示主窗口 / 分隔线 / 退出；
 * - 点图标**先消费系统通知的待决动作**再决定显示 —— 由调用方在 `onActivate` 里保证顺序。
 */
@Composable
fun NucleusTray(
    icon: Painter,
    tooltip: String,
    onActivate: () -> Unit,
    onShow: () -> Unit,
    onQuit: () -> Unit,
) {
    Tray(
        icon = icon,
        tooltip = tooltip,
        primaryAction = onActivate,
        menuContent = {
            Item(label = "显示主窗口", onClick = onShow)
            Divider()
            Item(label = "退出", onClick = onQuit)
        },
    )
}
