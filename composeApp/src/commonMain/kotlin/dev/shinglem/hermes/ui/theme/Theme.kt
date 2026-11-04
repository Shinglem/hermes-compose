package dev.shinglem.hermes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import dev.shinglem.hermes.core.config.ThemeStyle

/**
 * M3 主题：用 Compose 原生配色，不覆盖 `MaterialTheme` 的间距/圆角默认值
 * （`AGENTS.md` 硬约束 12：视觉稿不是像素基准）。
 *
 * [style] 决定整套配色的来路：
 * - [ThemeStyle.MATERIAL]：M3 默认色板；
 * - 其余风格（paper / ocean / sage / plum / amber）：[AppPalette] 里手写的亮 / 暗色板，
 *   只替换 M3 的语义角色，间距与圆角仍是默认值。
 *
 * Markdown 的配色**不在这里决定** —— 它由 `options.ui.markdown_style` 单独设置
 * （`follow` 才跟随 [style]），见 `dev.shinglem.hermes.markdown.MarkdownText`。
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: ThemeStyle = ThemeStyle.MATERIAL,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        style != ThemeStyle.MATERIAL -> paletteOf(style, darkTheme).toColorScheme(darkTheme)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        // 字体族随应用内置（Inter），不再依赖运行时的自带字体；中文由 Skia 回退系统字体。
        typography = appTypography(),
        content = content,
    )
}
