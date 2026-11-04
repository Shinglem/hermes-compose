package dev.shinglem.hermes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.shinglem.hermes.core.config.ThemeStyle

/**
 * 界面色板：只声明语义色，其余 M3 角色由 [toColorScheme] 推导。
 *
 * 文本对比度是硬指标（见 `AppPaletteContrastTest`）：正文 ≥ 11:1、次要文字 ≥ 4.5:1、
 * 强调色上的文字 ≥ 4.5:1（WCAG AA）。改色值前先跑那个测试 —— 之前 paper 的
 * `textMuted` 只有 3.0:1，观感就是「灰字看不清」。
 */
@Immutable
internal data class AppPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentWarm: Color,
    val divider: Color,
    val inlineCode: Color,
)

/**
 * 各风格的亮 / 暗色板。
 *
 * 只覆盖「看得见」的角色，`error` / `scrim` 沿用 M3 默认 —— 这些语义色硬凑会与背景打架。
 */
internal val PaperLight = AppPalette(
    background = Color(0xFFFDFCF9),
    surface = Color(0xFFF5EFE3),
    surfaceHigh = Color(0xFFEFE7D8),
    text = Color(0xFF2A2521),
    textMuted = Color(0xFF6A6055),
    accent = Color(0xFFA8433A),
    onAccent = Color.White,
    accentContainer = Color(0xFFF2DDD5),
    onAccentContainer = Color(0xFF5C1F1A),
    accentWarm = Color(0xFF9A4A20),
    divider = Color(0xFFD8C7B6),
    inlineCode = Color(0xFFF4E3DA),
)

internal val PaperDark = AppPalette(
    background = Color(0xFF191614),
    surface = Color(0xFF221E1A),
    surfaceHigh = Color(0xFF2C2723),
    text = Color(0xFFF0E9DF),
    textMuted = Color(0xFFB3A797),
    accent = Color(0xFFE0785F),
    onAccent = Color(0xFF2A1210),
    accentContainer = Color(0xFF4A241E),
    onAccentContainer = Color(0xFFFFDCD2),
    accentWarm = Color(0xFFE09A62),
    divider = Color(0xFF463D35),
    inlineCode = Color(0xFF3A2B28),
)

internal val OceanLight = AppPalette(
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFEAF0F6),
    surfaceHigh = Color(0xFFE1E9F1),
    text = Color(0xFF16202B),
    textMuted = Color(0xFF55637A),
    accent = Color(0xFF1F5FA8),
    onAccent = Color.White,
    accentContainer = Color(0xFFD7E5F5),
    onAccentContainer = Color(0xFF10375F),
    accentWarm = Color(0xFF2C6E8F),
    divider = Color(0xFFC8D6E4),
    inlineCode = Color(0xFFDEEAF7),
)

internal val OceanDark = AppPalette(
    background = Color(0xFF0E141B),
    surface = Color(0xFF161F28),
    surfaceHigh = Color(0xFF1D2833),
    text = Color(0xFFE6EDF4),
    textMuted = Color(0xFF9FB0C2),
    accent = Color(0xFF6FB0E8),
    onAccent = Color(0xFF06182B),
    accentContainer = Color(0xFF1C3A56),
    onAccentContainer = Color(0xFFCFE5FA),
    accentWarm = Color(0xFF79C0D8),
    divider = Color(0xFF2C3A48),
    inlineCode = Color(0xFF1B2E42),
)

internal val SageLight = AppPalette(
    background = Color(0xFFF8FAF6),
    surface = Color(0xFFEBF2E7),
    surfaceHigh = Color(0xFFE2EBDD),
    text = Color(0xFF1B241B),
    textMuted = Color(0xFF5A6A5B),
    accent = Color(0xFF2C6E3F),
    onAccent = Color.White,
    accentContainer = Color(0xFFD8EAD8),
    onAccentContainer = Color(0xFF14401F),
    accentWarm = Color(0xFF4E7A3A),
    divider = Color(0xFFC9D8C6),
    inlineCode = Color(0xFFE2F0E1),
)

internal val SageDark = AppPalette(
    background = Color(0xFF10150F),
    surface = Color(0xFF182018),
    surfaceHigh = Color(0xFF1F2A1F),
    text = Color(0xFFE3EBE1),
    textMuted = Color(0xFF9EAF9E),
    accent = Color(0xFF7CC48F),
    onAccent = Color(0xFF0A2012),
    accentContainer = Color(0xFF21402A),
    onAccentContainer = Color(0xFFCBEBD3),
    accentWarm = Color(0xFFA8C46A),
    divider = Color(0xFF2C392C),
    inlineCode = Color(0xFF1D2E21),
)

internal val PlumLight = AppPalette(
    background = Color(0xFFFBF9FC),
    surface = Color(0xFFF0EAF4),
    surfaceHigh = Color(0xFFE8E0EE),
    text = Color(0xFF221A28),
    textMuted = Color(0xFF60536B),
    accent = Color(0xFF6E338F),
    onAccent = Color.White,
    accentContainer = Color(0xFFE7D8F1),
    onAccentContainer = Color(0xFF3A1450),
    accentWarm = Color(0xFF8A4C6E),
    divider = Color(0xFFD9CBE3),
    inlineCode = Color(0xFFF1E4F8),
)

internal val PlumDark = AppPalette(
    background = Color(0xFF141017),
    surface = Color(0xFF1C1622),
    surfaceHigh = Color(0xFF241D2B),
    text = Color(0xFFEDE5F2),
    textMuted = Color(0xFFAC9FB6),
    accent = Color(0xFFC08BE0),
    onAccent = Color(0xFF1E0C29),
    accentContainer = Color(0xFF35244A),
    onAccentContainer = Color(0xFFE6D4F6),
    accentWarm = Color(0xFFE0A0C0),
    divider = Color(0xFF372C40),
    inlineCode = Color(0xFF2A1F35),
)

internal val AmberLight = AppPalette(
    background = Color(0xFFFFFBF2),
    surface = Color(0xFFF7EEDC),
    surfaceHigh = Color(0xFFF0E4CE),
    text = Color(0xFF2A2114),
    textMuted = Color(0xFF6B5C46),
    accent = Color(0xFFA9620F),
    onAccent = Color.White,
    accentContainer = Color(0xFFF5E3C2),
    onAccentContainer = Color(0xFF4A2A05),
    accentWarm = Color(0xFF8A5A1A),
    divider = Color(0xFFDFCCAB),
    inlineCode = Color(0xFFF8EAD3),
)

internal val AmberDark = AppPalette(
    background = Color(0xFF17130E),
    surface = Color(0xFF201A13),
    surfaceHigh = Color(0xFF2A2318),
    text = Color(0xFFF1E8DA),
    textMuted = Color(0xFFB5A68D),
    accent = Color(0xFFE5A64F),
    onAccent = Color(0xFF241503),
    accentContainer = Color(0xFF3A2A13),
    onAccentContainer = Color(0xFFF6DFB4),
    accentWarm = Color(0xFFE8C078),
    divider = Color(0xFF3B3122),
    inlineCode = Color(0xFF2E2517),
)

/**
 * 取某个风格的色板。[ThemeStyle.MATERIAL] 没有自带色板（用 M3 默认），这里直接报错 ——
 * 调用方（[HermesTheme]）要在那之前分流。
 */
internal fun paletteOf(style: ThemeStyle, dark: Boolean): AppPalette = when (style) {
    ThemeStyle.PAPER -> if (dark) PaperDark else PaperLight
    ThemeStyle.OCEAN -> if (dark) OceanDark else OceanLight
    ThemeStyle.SAGE -> if (dark) SageDark else SageLight
    ThemeStyle.PLUM -> if (dark) PlumDark else PlumLight
    ThemeStyle.AMBER -> if (dark) AmberDark else AmberLight
    ThemeStyle.MATERIAL -> error("material 用 M3 默认色板，不走 AppPalette")
}

/**
 * 色板 → M3 [ColorScheme]。
 *
 * `surfaceVariant` / `onSurfaceVariant` 是次要文字用得最多的一对（提示、时间、分组标题），
 * 所以显式绑到 [AppPalette.textMuted]；`surfaceContainer*` 全部落到纸面层级，
 * 免得 Card / Dialog 在纸上透出 M3 默认的紫灰色。
 */
internal fun AppPalette.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        inversePrimary = accent,
        secondary = accentWarm,
        onSecondary = onAccent,
        secondaryContainer = inlineCode,
        onSecondaryContainer = text,
        tertiary = accentWarm,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = onAccentContainer,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = textMuted,
        surfaceTint = accent,
        surfaceBright = if (dark) surfaceHigh else background,
        surfaceDim = if (dark) background else surface,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        outline = divider,
        outlineVariant = divider.copy(alpha = 0.55f),
        inverseSurface = text,
        inverseOnSurface = background,
    )
}
