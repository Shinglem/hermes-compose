package dev.shinglem.hermes.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shinglem.hermes.core.config.ThemeStyle
import dev.shinglem.hermes.ui.theme.paletteOf
import io.github.shinglem.markdown.MarkdownTheme
import io.github.shinglem.markdown.text.mdColors
import io.github.shinglem.markdown.text.mdDimens
import io.github.shinglem.markdown.text.mdPadding
import io.github.shinglem.markdown.text.mdTypography

/**
 * 纸面 Markdown 主题：排版沿用 Outside Paper（标题小一档、行高 1.6、块间距收窄），
 * **颜色取本项目的 paper 色板**（`ui.theme.AppPalette`）。
 *
 * 为什么不直接用 markdown-one 的 `outsidePaperTheme`：它的正文色是 `#5A524A`（7.4:1）、
 * 次要文字 `#9A9080`（3.0:1），在深色桌面上看久了就是「灰字发糊」。这里的色值与
 * 界面侧同一份，`AppPaletteContrastTest` 会把对比度守住。
 */
@Composable
internal fun paperMarkdownTheme(dark: Boolean): MarkdownTheme {
    val palette = paletteOf(ThemeStyle.PAPER, dark)
    val base = MaterialTheme.typography

    fun style(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        color: Color = palette.text,
        italic: Boolean = false,
    ): TextStyle = base.bodyMedium.copy(
        fontSize = size.sp,
        lineHeight = (size * 1.6f).sp,
        fontWeight = weight,
        color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )

    val body = style(15)

    return MarkdownTheme(
        colors = mdColors(
            text = palette.text,
            codeBackground = palette.surface,
            inlineCodeBackground = palette.inlineCode,
            dividerColor = palette.divider,
            tableBackground = palette.surface,
            highlightText = palette.accent,
            highlightBackground = palette.inlineCode,
        ),
        typography = mdTypography(
            h1 = style(24, FontWeight.Bold, palette.accent),
            h2 = style(20, FontWeight.SemiBold),
            h3 = style(17, FontWeight.SemiBold),
            h4 = style(16, FontWeight.Medium, palette.accentWarm),
            h5 = style(15, FontWeight.Medium),
            h6 = style(14, FontWeight.Medium, palette.textMuted),
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            quote = style(15, color = palette.textMuted, italic = true),
            code = style(13).copy(fontFamily = FontFamily.Monospace),
            inlineCode = style(14, color = palette.accent).copy(fontFamily = FontFamily.Monospace),
            table = body,
            textLink = TextLinkStyles(
                style = SpanStyle(color = palette.accent, fontWeight = FontWeight.Medium),
            ),
        ),
        dimens = mdDimens(
            dividerThickness = 1.dp,
            codeBackgroundCornerSize = 8.dp,
            blockQuoteThickness = 3.dp,
            tableCornerSize = 6.dp,
        ),
        padding = mdPadding(),
        background = palette.background,
    )
}
