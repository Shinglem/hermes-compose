package dev.shinglem.hermes.ui.notification

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.core.config.ThemeMode
import dev.shinglem.hermes.platform.NotificationInbox
import dev.shinglem.hermes.ui.theme.HermesTheme
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlinx.coroutines.delay

/** 距屏幕工作区边缘的距离（dp）。 */
private const val EDGE_MARGIN = 16

/**
 * 通知窗（右下角，无边框 + 透明底，只为画一张 M3 卡片）。
 *
 * 三条必须守住的约束：
 * 1. **不抢焦点**（`focusable = false`）：用户正在输入时弹出来不该把光标从输入框拿走。
 * 2. **与主窗口平级**：由 `Main` 在 `application {}` 里调用，而不是塞进主 `Window` 内部 ——
 *    托盘模式下主窗口不进入组合，审批通知同样要能弹。
 * 3. **透明 + 阴影留白**：卡片自己画圆角与阴影，窗口内容要多留 [NOTIFICATION_SHADOW_MARGIN]，
 *    否则阴影会被窗口边缘裁掉。
 */
@Composable
fun NotificationOverlay() {
    val request by NotificationInbox.current.collectAsState()
    val current = request ?: return
    val options by AppOptionsHolder.state.collectAsState()
    val darkTheme = when (options.ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val state = remember(current.id) {
        // 只在显示那一刻算一次位置：中途改分辨率也不该把窗口拽到别处。
        val area = workArea()
        val width = NOTIFICATION_CARD_WIDTH.value + NOTIFICATION_SHADOW_MARGIN.value * 2
        val height = notificationCardHeight(current.actions.isNotEmpty()).value +
            NOTIFICATION_SHADOW_MARGIN.value * 2
        val scale = screenScale()
        val x = (area.x + area.width) / scale - width - EDGE_MARGIN
        val y = (area.y + area.height) / scale - height - EDGE_MARGIN
        WindowState(
            position = WindowPosition(x.dp, y.dp),
            size = DpSize(width.dp, height.dp),
        )
    }

    // 自动收起：有按钮的多留一会儿，纯提示短一点。
    LaunchedEffect(current.id) {
        if (current.autoDismissMs > 0) {
            delay(current.autoDismissMs)
            NotificationInbox.dismiss(current.id)
        }
    }

    Window(
        onCloseRequest = { NotificationInbox.dismiss(current.id) },
        state = state,
        title = current.title,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        HermesTheme(darkTheme = darkTheme, style = options.ui.style) {
            Box(modifier = Modifier.fillMaxSize().padding(NOTIFICATION_SHADOW_MARGIN)) {
                NotificationCard(
                    request = current,
                    onDismiss = { NotificationInbox.dismiss(current.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 可用工作区（避开任务栏），单位是 AWT 的用户坐标。
 *
 * 取鼠标所在屏：多显示器时通知应该出现在用户正看着的那块。
 */
private fun workArea(): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val mouse = java.awt.MouseInfo.getPointerInfo()?.location
    val device = mouse?.let { point ->
        environment.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(point) }
    } ?: environment.defaultScreenDevice
    val bounds = device.defaultConfiguration.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(device.defaultConfiguration)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

/**
 * AWT 用户坐标 → Compose dp 的换算比例。
 *
 * Compose 的 dp 在 100% 缩放下与 AWT 用户坐标 1:1，但高 DPI 下两边各自乘过一次系统缩放，
 * 这里用 AWT 自己算出的 `defaultTransform` 对齐，避免通知跑到屏幕外。
 */
private fun screenScale(): Float = runCatching {
    val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
    val transform = device.defaultConfiguration.defaultTransform
    transform.scaleX.toFloat().takeIf { it > 0f } ?: 1f
}.getOrDefault(1f)
