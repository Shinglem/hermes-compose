package dev.shinglem.hermes.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 快捷键解析：按归一化字符匹配，Shift 改字符的键位也要命中。 */
class ShortcutsTest {

    private fun key(text: String, ctrl: Boolean = true, shift: Boolean = false) =
        appShortcutOf(ctrl = ctrl, shift = shift, codePoint = text[0].code)

    @Test
    fun `没有 Ctrl 一律不认`() {
        assertNull(appShortcutOf(ctrl = false, shift = false, codePoint = 'n'.code))
        assertNull(appShortcutOf(ctrl = false, shift = true, codePoint = '`'.code))
    }

    @Test
    fun `基础组合`() {
        assertEquals(AppShortcut.NEW_SESSION, key("n"))
        assertEquals(AppShortcut.NEW_SESSION, key("N", shift = true))
        assertEquals(AppShortcut.OPEN_SETTINGS, key(","))
        assertEquals(AppShortcut.TERMINAL_TOGGLE, key("`"))
        assertEquals(AppShortcut.TERMINAL_NEW_TAB, key("`", shift = true))
    }

    @Test
    fun `Shift 改变字符的键位也能命中会话切换`() {
        // US 布局下 Shift+[ 给出的是 '{'，Shift+] 是 '}'。
        assertEquals(AppShortcut.PREV_SESSION, key("["))
        assertEquals(AppShortcut.PREV_SESSION, key("{"))
        assertEquals(AppShortcut.NEXT_SESSION, key("]"))
        assertEquals(AppShortcut.NEXT_SESSION, key("}"))
    }

    @Test
    fun `Ctrl+Tab 与 Ctrl+Shift+Tab 切终端标签`() {
        assertEquals(AppShortcut.TERMINAL_NEXT_TAB, key("\t"))
        assertEquals(AppShortcut.TERMINAL_PREV_TAB, key("\t", shift = true))
    }

    @Test
    fun `未定义的组合返回空`() {
        assertNull(key("q"))
        assertNull(appShortcutOf(ctrl = true, shift = false, codePoint = 0))
    }

    @Test
    fun `只有打开设置不受模式限制`() {
        assertFalse(AppShortcut.OPEN_SETTINGS.requiresSessionMode())
        assertTrue(AppShortcut.TERMINAL_TOGGLE.requiresSessionMode())
        assertTrue(AppShortcut.NEW_SESSION.requiresSessionMode())
        assertTrue(AppShortcut.NEXT_SESSION.requiresSessionMode())
    }
}
