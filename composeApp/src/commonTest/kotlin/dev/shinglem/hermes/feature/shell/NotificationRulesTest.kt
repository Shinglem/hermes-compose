package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.feature.chat.SessionKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `待修复问题.md` 第 2 条的验收判据：只有「前台 + 同一个 profile + 选中的就是这个会话」
 * 才算用户正看着，其余一律提醒。
 */
class NotificationRulesTest {

    private val mine = SessionKey(profile = null, sessionId = "s1")
    private val other = SessionKey(profile = null, sessionId = "s2")

    @Test
    fun `foreground and same session is not reminded`() {
        assertFalse(
            shouldRemindForSession(
                target = mine,
                foreground = true,
                activeProfile = null,
                selectedSessionId = "s1",
            ),
        )
    }

    @Test
    fun `foreground but another session is reminded`() {
        assertTrue(
            shouldRemindForSession(
                target = mine,
                foreground = true,
                activeProfile = null,
                selectedSessionId = "s2",
            ),
        )
    }

    @Test
    fun `foreground but another profile is reminded`() {
        assertTrue(
            shouldRemindForSession(
                target = SessionKey(profile = "work", sessionId = "s1"),
                foreground = true,
                activeProfile = "default",
                selectedSessionId = "s1",
            ),
        )
    }

    @Test
    fun `background is always reminded`() {
        assertTrue(
            shouldRemindForSession(
                target = mine,
                foreground = false,
                activeProfile = null,
                selectedSessionId = "s1",
            ),
        )
    }

    @Test
    fun `unknown session is reminded`() {
        assertTrue(
            shouldRemindForSession(
                target = null,
                foreground = true,
                activeProfile = null,
                selectedSessionId = "s1",
            ),
        )
    }

    @Test
    fun `same request is reminded only once`() {
        val gate = ReminderGate()
        assertTrue(gate.shouldRemind("approval:r1"))
        assertFalse(gate.shouldRemind("approval:r1"))
        assertTrue(gate.shouldRemind("approval:r2"))
    }

    @Test
    fun `forgotten request can be reminded again`() {
        val gate = ReminderGate()
        assertTrue(gate.shouldRemind("clarify:r1"))
        gate.forget("clarify:r1")
        assertTrue(gate.shouldRemind("clarify:r1"))
    }
}
