package dev.shinglem.hermes.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalSettingsTest {

    @Test
    fun `color parses rrggbb and aarrggbb`() {
        assertEquals(0xFFD5DBE5.toInt(), TerminalSettings.parseColor("#D5DBE5"))
        assertEquals(0xFFD5DBE5.toInt(), TerminalSettings.parseColor("d5dbe5"))
        assertEquals(0x80D5DBE5.toInt(), TerminalSettings.parseColor("#80D5DBE5"))
        assertNull(TerminalSettings.parseColor("#XYZ"))
        assertNull(TerminalSettings.parseColor("#12345"))
    }

    @Test
    fun `color formats without alpha`() {
        assertEquals("#D5DBE5", TerminalSettings.formatColor(0xFFD5DBE5.toInt()))
        assertEquals("#000000", TerminalSettings.formatColor(0xFF000000.toInt()))
    }

    @Test
    fun `normalize clamps font size and scrollback`() {
        val clamped = TerminalSettings(fontSize = 99f, scrollbackLines = 1).normalized()
        assertEquals(TerminalSettings.MAX_FONT_SIZE, clamped.fontSize)
        assertEquals(TerminalSettings.MIN_SCROLLBACK, clamped.scrollbackLines)

        val small = TerminalSettings(fontSize = 1f, scrollbackLines = 999_999).normalized()
        assertEquals(TerminalSettings.MIN_FONT_SIZE, small.fontSize)
        assertEquals(TerminalSettings.MAX_SCROLLBACK, small.scrollbackLines)
    }
}
