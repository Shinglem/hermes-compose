package dev.shinglem.hermes.shell.window

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle

/**
 * 把 M3 主题翻译成 Nucleus 标题栏配色。
 *
 * Tao 的 `DecoratedWindow` 不自带标题栏，`TitleBar {}` 也不读 `MaterialTheme` —— 它读的是
 * `LocalTitleBarStyle`（默认一套固定的深灰配色）。所以在带主题的窗口里直接调 `TitleBar {}`，
 * 得到的是「深灰底 + 主题深色字」这种看不清的组合（S6 首次实跑就是这个情况）。
 *
 * 这里只映射「看得见」的角色：
 * - 背景 = `surfaceContainer`（与主界面同族，靠边框与内容区区分，不用纯白/纯黑）；
 * - 文字/图标 = `onSurfaceVariant`（次要文字色，标题本就该弱于正文）；
 * - 悬停/按下 = `onSurface` 的低透明度叠加，与 M3 的 state layer 观感一致；
 * - 关闭按钮不做红色 —— Windows 11 的普通窗口也不做，红色只留给对话框。
 *
 * 尺寸一概不覆盖：`TitleBarStyle` 的 `metrics` 走 Nucleus 默认（窗口高度、按钮尺寸按系统档位），
 * 符合 `AGENTS.md` 硬约束 12「不为对齐视觉稿而写死尺寸」。
 */
@Composable
fun rememberHermesTitleBarStyle(): TitleBarStyle {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        TitleBarStyle(
            colors = TitleBarColors(
                background = scheme.surfaceContainer,
                inactiveBackground = scheme.surfaceContainer,
                content = scheme.onSurfaceVariant,
                border = scheme.outlineVariant,
                fullscreenControlButtonsBackground = scheme.surfaceContainer,
                iconButtonHoveredBackground = scheme.onSurface.copy(alpha = 0.08f),
                iconButtonPressedBackground = scheme.onSurface.copy(alpha = 0.14f),
                controlButtonIconColor = scheme.onSurfaceVariant,
                controlButtonIconHoverColor = scheme.onSurface,
            ),
            // `TitleBarStyle.metrics` 没有默认值，必须显式给。用 Nucleus 的默认度量
            // （高度、渐变位置、标题栏按钮尺寸都按系统档位），不覆盖任何数值。
            metrics = TitleBarMetrics(),
        )
    }
}
