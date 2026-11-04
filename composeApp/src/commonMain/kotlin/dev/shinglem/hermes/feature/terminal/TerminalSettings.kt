package dev.shinglem.hermes.feature.terminal

import kotlinx.serialization.Serializable

/**
 * 终端外观设置（用户可改，存 `config/state.json`；见 ADR-042）。
 *
 * 颜色用 ARGB 整数，界面上按 `#RRGGBB` 编辑。
 * **字体/字号只对新开的标签生效** —— JediTerm 的字体在 `TerminalPanel` 构造时定死，
 * 改已有 widget 会重建 PTY，代价不可接受。
 */
@Serializable
data class TerminalSettings(
    val fontFamily: String? = null,
    val fontSize: Float = 14f,
    val scrollbackLines: Int = 5_000,
    val foregroundArgb: Int = DEFAULT_FOREGROUND,
    val backgroundArgb: Int = DEFAULT_BACKGROUND,
) {
    fun normalized(): TerminalSettings = copy(
        fontSize = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
        scrollbackLines = scrollbackLines.coerceIn(MIN_SCROLLBACK, MAX_SCROLLBACK),
    )

    /** `#RRGGBB` / `RRGGBB` / `#AARRGGBB` → Int；解析失败返回 null。 */
    companion object {
        const val DEFAULT_FOREGROUND = 0xFFD5DBE5.toInt()
        const val DEFAULT_BACKGROUND = 0xFF05070B.toInt()

        const val MIN_FONT_SIZE = 11f
        const val MAX_FONT_SIZE = 20f
        const val MIN_SCROLLBACK = 1_000
        const val MAX_SCROLLBACK = 20_000

        fun parseColor(text: String): Int? {
            val raw = text.trim().removePrefix("#")
            return when (raw.length) {
                6 -> raw.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                8 -> raw.toLongOrNull(16)?.toInt()
                else -> null
            }
        }

        fun formatColor(argb: Int): String {
            val hex = (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
            return "#$hex"
        }
    }
}
