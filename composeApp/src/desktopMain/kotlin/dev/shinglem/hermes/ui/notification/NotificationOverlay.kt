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
import dev.shinglem.hermes.core.config.ThemeStyle
import dev.shinglem.hermes.platform.NotificationInbox
import dev.shinglem.hermes.platform.NotificationRequest
import dev.shinglem.hermes.ui.theme.HermesTheme
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlinx.coroutines.delay

/** 距屏幕工作区边缘的距离（dp）。 */
private const val EDGE_MARGIN = 16
/** 相邻两张卡片之间的间距（dp）。 */
private const val STACK_GAP = 8

/**
 * 通知窗（右下角，无边框 + 透明底，只为画 M3 卡片）。
 *
 * 三条必须守住的约束：
 * 1. **不抢焦点**（`focusable = false`）：用户正在输入时弹出来不该把光标从输入框拿走。
 * 2. **与主窗口平级**：由 `Main` 在 `application {}` 里调用，而不是塞进主 `Window` 内部 ——
 *    托盘模式下主窗口不进入组合，审批通知同样要能弹。
 * 3. **透明 + 阴影留白**：卡片自己画圆角与阴影，窗口内容要多留 [NOTIFICATION_SHADOW_MARGIN]，
 *    否则阴影会被窗口边缘裁掉。
 *
 * 多条并存时**纵向堆叠**（最多 [NotificationInbox.MAX_VISIBLE] 条），最新的在最下面（贴着任务栏），
 * 与 Windows 自己的通知中心一致；每条一个独立窗口，自动收起互不干扰。
 * 超过上限的排队：上面某条消失后，排队的自动补到最下面。
 */
@Composable
fun NotificationOverlay() {
    val active by NotificationInbox.active.collectAsState()
    if (active.isEmpty()) return
    val options by AppOptionsHolder.state.collectAsState()
    val darkTheme = when (options.ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // 只在条数变化时算一次屏幕几何：中途改分辨率也不该把窗口拽到别处。
    val geometry = remember(active.size) { overlayGeometry() }
    val heights = active.asReversed().map {
        notificationCardHeight(it.actions.isNotEmpty()).value + NOTIFICATION_SHADOW_MARGIN.value * 2
    }
    val offsets = stackOffsets(heights, geometry.bottom, STACK_GAP.toFloat())
    active.asReversed().forEachIndexed { index, request ->
        NotificationWindow(
            request = request,
            x = geometry.left,
            y = offsets[index],
            width = geometry.width,
            height = heights[index],
            darkTheme = darkTheme,
            themeStyle = options.ui.style,
        )
    }
}

/**
 * 堆叠位置（纯函数，便于单测）：输入是从**下往上**的卡片高度与堆的底边，输出每条窗口的 y。
 *
 * 第一条（最新）贴着 [bottom]，后面的依次往上叠，中间留 [gap]。
 */
internal fun stackOffsets(heights: List<Float>, bottom: Float, gap: Float): List<Float> {
    var consumed = 0f
    return heights.map { height ->
        val y = bottom - height - consumed
        consumed += height + gap
        y
    }
}

/** 通知窗集合在屏幕上的几何（dp）。 */
private class OverlayGeometry(val left: Float, val width: Float, val bottom: Float)

private fun overlayGeometry(): OverlayGeometry {
    val area = workArea()
    val scale = screenScale()
    val width = NOTIFICATION_CARD_WIDTH.value + NOTIFICATION_SHADOW_MARGIN.value * 2
    return OverlayGeometry(
        left = (area.x + area.width) / scale - width - EDGE_MARGIN,
        width = width,
        bottom = (area.y + area.height) / scale - EDGE_MARGIN,
    )
}

/**
 * 一条通知一个窗口。
 *
 * 位置会随上面条目的增减而变化，所以 `WindowState` 的 remember key 带上 y：
 * 上面那条收起后，下面这条跟着挪到新位置。
 */
@Composable
private fun NotificationWindow(
    request: NotificationRequest,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    darkTheme: Boolean,
    themeStyle: ThemeStyle,
) {
    val state = remember(request.id, x, y) {
        WindowState(
            position = WindowPosition(x.dp, y.dp),
            size = DpSize(width.dp, height.dp),
        )
    }

    // 每条自己的计时：先到的先消失，互不干扰。
    LaunchedEffect(request.id) {
        if (request.autoDismissMs > 0) {
            delay(request.autoDismissMs)
            NotificationInbox.dismiss(request.id)
        }
    }

    Window(
        onCloseRequest = { NotificationInbox.dismiss(request.id) },
        state = state,
        title = request.title,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        HermesTheme(darkTheme = darkTheme, style = themeStyle) {
            Box(modifier = Modifier.fillMaxSize().padding(NOTIFICATION_SHADOW_MARGIN)) {
                NotificationCard(
                    request = request,
                    onDismiss = { NotificationInbox.dismiss(request.id) },
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
