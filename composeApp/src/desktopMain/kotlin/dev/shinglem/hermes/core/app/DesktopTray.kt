package dev.shinglem.hermes.core.app

import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * 托盘菜单的一项。
 *
 * 只支持普通条目与分隔线：托盘菜单是系统级入口，不需要勾选、快捷键、子菜单。
 */
sealed interface TrayMenuItem {
    data class Action(
        val text: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : TrayMenuItem

    data object Separator : TrayMenuItem
}

/**
 * 托盘图标 + 右键菜单。
 *
 * **不用** `androidx.compose.ui.window.Tray`：它的菜单是 AWT `PopupMenu`（原生 Win32 菜单），
 * 菜单文字交给 Win32 前按**默认字符集**（`file.encoding`，本项目为 UTF-8）转 ANSI，中文丢字形，
 * 全部渲染成方框；`MenuItem.setFont` 对原生菜单又无效，换字体也绕不过去。
 * 唯一的就地修法是 `-Dfile.encoding=GBK`（实测可恢复中文），但项目三层编码统一 UTF-8，
 * 不能为一个菜单改掉。上游问题见 CMP-1936（仍 Open）/ CMP-4486（以 wait for reply 关闭）。
 *
 * 这里改成 Swing `JPopupMenu`：Java2D 渲染、走 LAF 字体，中文正常；边缘翻转、失焦/按 Esc
 * 自动关闭也都是 Swing 自带的，不需要自己写一套弹窗生命周期。
 */
class DesktopTray(
    private val icon: Image,
    private val tooltip: String,
    private val onActivate: () -> Unit,
    private val menu: () -> List<TrayMenuItem>,
) {
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (trayIcon != null) return
        if (!SystemTray.isSupported()) {
            System.err.println("Tray is not supported on the current platform.")
            return
        }
        val iconToAdd = TrayIcon(icon).apply {
            isImageAutoSize = true
            toolTip = tooltip
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.isPopupTrigger) showMenu() else if (event.clickCount >= 2) onActivate()
                }
            })
        }
        try {
            SystemTray.getSystemTray().add(iconToAdd)
        } catch (failure: Exception) {
            // 托盘区满 / 被策略禁用：不该因此让整个应用起不来。
            System.err.println("Cannot add tray icon: $failure")
            return
        }
        // 气泡通知与"点托盘图标"走同一个入口：有挂起的"跳到某个会话"动作就先执行它，
        // 否则把窗口拉到前台。
        iconToAdd.addActionListener {
            if (!dev.shinglem.hermes.platform.SystemNotifications.consumePendingAction()) onActivate()
        }
        trayIcon = iconToAdd
        dev.shinglem.hermes.platform.SystemNotifications.attach(iconToAdd, onActivate)
    }

    fun dispose() {
        dev.shinglem.hermes.platform.SystemNotifications.attach(null)
        trayIcon?.let { iconToRemove -> runCatching { SystemTray.getSystemTray().remove(iconToRemove) } }
        trayIcon = null
    }

    private fun showMenu() {
        val entries = menu()
        if (entries.isEmpty()) return
        val location = MouseInfo.getPointerInfo()?.location ?: return

        val popup = JPopupMenu()
        entries.forEach { entry ->
            when (entry) {
                is TrayMenuItem.Action -> popup.add(
                    JMenuItem(entry.text).apply {
                        isEnabled = entry.enabled
                        addActionListener { entry.onClick() }
                    },
                )

                TrayMenuItem.Separator -> popup.addSeparator()
            }
        }

        // `JPopupMenu.show` 要求 invoker 已经显示；托盘常驻时主窗口可能是隐藏的，
        // 用一个 1×1 的透明窗口顶着，菜单一关就销毁。
        val invoker = JWindow().apply {
            setBounds(location.x, location.y, 1, 1)
            isAlwaysOnTop = true
            // 不支持 per-window 透明的环境下退化成 1px 的点，不影响功能。
            runCatching { opacity = 0f }
        }
        popup.addPopupMenuListener(
            object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = invoker.dispose()
                override fun popupMenuCanceled(event: PopupMenuEvent) = invoker.dispose()
            },
        )
        invoker.isVisible = true
        popup.show(invoker, 0, 0)
    }
}
