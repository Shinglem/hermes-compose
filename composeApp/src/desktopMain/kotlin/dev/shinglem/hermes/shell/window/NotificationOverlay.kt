package dev.shinglem.hermes.shell.window

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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.core.config.ThemeMode
import dev.shinglem.hermes.core.config.ThemeStyle
import dev.shinglem.hermes.platform.NotificationInbox
import dev.shinglem.hermes.platform.NotificationRequest
import dev.shinglem.hermes.ui.notification.NotificationCard
import dev.shinglem.hermes.ui.notification.NOTIFICATION_CARD_WIDTH
import dev.shinglem.hermes.ui.notification.NOTIFICATION_SHADOW_MARGIN
import dev.shinglem.hermes.ui.notification.notificationCardHeight
import dev.shinglem.hermes.ui.theme.HermesTheme
import dev.nucleusframework.application.DecoratedWindow
import kotlinx.coroutines.delay

/** 距屏幕工作区边缘的距离（dp）。 */
internal const val EDGE_MARGIN = 16
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
 *
 * @param workArea 当前屏幕的工作区（dp）。**必须由调用方注入**：算它需要 Tao 的窗口句柄
 *   （`NucleusWindow.unsafe.taoWindow`），而那个句柄只有 `DecoratedWindow` 作用域里才有；
 *   在 `nucleusApplication` 顶层拿不到。早期实现在这里直接调 AWT 的
 *   `GraphicsEnvironment`，能跑但把 AWT 留在了窗口层（ADR-073）。
 */
@Composable
fun NotificationOverlay(workArea: () -> WorkArea) {
    val active by NotificationInbox.active.collectAsState()
    if (active.isEmpty()) return
    val options by AppOptionsHolder.state.collectAsState()
    val darkTheme = when (options.ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // 只在条数变化时算一次屏幕几何：中途改分辨率也不该把窗口拽到别处。
    val geometry = remember(active.size) { overlayGeometry(workArea()) }
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
internal class OverlayGeometry(val left: Float, val width: Float, val bottom: Float)

/**
 * 屏幕工作区（dp）：`left/top` 是工作区左上角，`width/height` 是尺寸。
 *
 * 由调用方注入，因为拿它需要窗口句柄（见 [NotificationOverlay] 的参数说明）。
 */
class WorkArea(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun overlayGeometry(area: WorkArea): OverlayGeometry {
    val width = NOTIFICATION_CARD_WIDTH.value + NOTIFICATION_SHADOW_MARGIN.value * 2
    return OverlayGeometry(
        left = area.left + area.width - width - EDGE_MARGIN,
        width = width,
        bottom = area.top + area.height - EDGE_MARGIN,
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

    // Tao 下的通知窗（ADR-067）。参数对应旧 AWT `Window` 的四个要求：
    //  - `transparent = true` + `undecorated = true` → 无边框、逐像素透明
    //    （卡片自己画圆角与阴影，见 NOTIFICATION_SHADOW_MARGIN）
    //  - `focusable = false` → **不抢焦点**（用户正在输入时不该把光标拿走）
    //  - `alwaysOnTop = true` → 盖在普通窗口上
    //  - `hiddenFromDock = true` → 不出现在任务栏（通知不是独立应用窗口）
    // `visible` 恒为 true：条目在集合里就显示，移除即消失（不需要 show/hide 动画）。
    DecoratedWindow(
        onCloseRequest = { NotificationInbox.dismiss(request.id) },
        state = state,
        visible = true,
        title = request.title,
        resizable = false,
        minimizable = false,
        maximizable = false,
        focusable = false,
        alwaysOnTop = true,
        undecorated = true,
        hiddenFromDock = true,
        transparent = true,
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
 * 从 Tao 的显示器信息构造 [WorkArea]（dp）。
 *
 * **数据源是 Nucleus 的原生桥**（`TaoMonitors.all(taoWindow)` → `workAreaPx` +
 * `scaleFactor`），不是 AWT。实测（`monitorProbe`，Windows / 100% 缩放）：
 *
 * ```
 * TaoMonitors: work=IntRect(0, 38, 2560, 1392) scale=1.0 primary=true
 * AWT        : bounds=0,0,2560,1440 scale=1.0  → insets 上 38 / 下 48（与上面一致）
 * ```
 *
 * 两边数值相同，但前者不依赖 `GraphicsEnvironment` / `Toolkit` / `MouseInfo`。
 * 顺带解决了 ADR-067 记的遗留问题：多屏下 `MouseInfo` 返回垃圾值，通知会跑到错误屏幕。
 * 这里取**主显示器**（`TaoMonitors.primary`）—— 主窗口在哪块屏就贴哪块屏更理想，
 * 但那需要窗口几何的实时订阅，超出本次收尾范围，已记入 ADR-073 的后续项。
 */
fun taoWorkArea(taoWindow: dev.nucleusframework.window.tao.TaoWindow): WorkArea {
    val monitor = dev.nucleusframework.window.tao.TaoMonitors.primary(taoWindow)
    val px = monitor.workAreaPx
    val scale = monitor.scaleFactor.takeIf { it > 0f } ?: 1f
    return WorkArea(
        left = px.left / scale,
        top = px.top / scale,
        width = px.width / scale,
        height = px.height / scale,
    )
}
