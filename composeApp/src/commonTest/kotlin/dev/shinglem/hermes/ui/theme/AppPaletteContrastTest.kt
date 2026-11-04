package dev.shinglem.hermes.ui.theme

import androidx.compose.ui.graphics.Color
import dev.shinglem.hermes.core.config.ThemeStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 色板的可读性门槛。
 *
 * 起因：paper 原来的 `text` 只有 7.4:1、`textMuted` 只有 3.0:1，实际观感就是「灰字看不清」。
 * 这里把观感固化成数字，改色值（或新增风格）时先跑它：
 * - 正文 ≥ 11:1（远高于 AA 的 4.5:1，桌面小字号下才不糊）；
 * - 次要文字 ≥ 4.5:1（AA 正文标准）；
 * - 强调色上的文字 ≥ 4.5:1，强调容器上的文字 ≥ 7:1。
 */
class AppPaletteContrastTest {

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private val styles: List<ThemeStyle> = ThemeStyle.entries.filter { it != ThemeStyle.MATERIAL }

    @Test
    fun `除 material 外每种风格都有亮暗两套色板`() {
        assertTrue(styles.size >= 5, "至少要有 paper / ocean / sage / plum / amber：$styles")
        for (style in styles) {
            assertTrue(paletteOf(style, dark = false) != paletteOf(style, dark = true), "$style 亮暗色板相同")
        }
    }

    @Test
    fun `正文与次要文字达到可读门槛`() {
        for (style in styles) {
            for (dark in listOf(false, true)) {
                val p = paletteOf(style, dark)
                val tag = "$style/${if (dark) "dark" else "light"}"
                assertTrue(
                    contrast(p.text, p.background) >= 11.0,
                    "$tag 正文对比度 ${contrast(p.text, p.background)} < 11",
                )
                assertTrue(
                    contrast(p.text, p.surface) >= 11.0,
                    "$tag 正文在 surface 上 ${contrast(p.text, p.surface)} < 11",
                )
                assertTrue(
                    contrast(p.textMuted, p.background) >= 4.5,
                    "$tag 次要文字对比度 ${contrast(p.textMuted, p.background)} < 4.5",
                )
                assertTrue(
                    contrast(p.textMuted, p.surface) >= 4.5,
                    "$tag 次要文字在 surface 上 ${contrast(p.textMuted, p.surface)} < 4.5",
                )
            }
        }
    }

    @Test
    fun `强调色与其上的文字对比达标`() {
        for (style in styles) {
            for (dark in listOf(false, true)) {
                val p = paletteOf(style, dark)
                val tag = "$style/${if (dark) "dark" else "light"}"
                assertTrue(
                    contrast(p.onAccent, p.accent) >= 4.5,
                    "$tag onAccent/accent ${contrast(p.onAccent, p.accent)} < 4.5",
                )
                assertTrue(
                    contrast(p.onAccentContainer, p.accentContainer) >= 7.0,
                    "$tag onAccentContainer/accentContainer " +
                        "${contrast(p.onAccentContainer, p.accentContainer)} < 7",
                )
            }
        }
    }

    @Test
    fun `正文与次要文字角色绑定正确`() {
        for (style in styles) {
            val p = paletteOf(style, dark = false)
            val scheme = p.toColorScheme(dark = false)
            assertTrue(
                scheme.onSurfaceVariant == p.textMuted,
                "$style 的 onSurfaceVariant 没绑到 textMuted（提示 / 时间 / 分组标题会偏淡）",
            )
            assertTrue(scheme.onSurface == p.text, "$style 的 onSurface 没绑到 text")
            assertTrue(scheme.background == p.background, "$style 的 background 没绑到色板底色")
        }
    }
}
