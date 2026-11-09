package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.feature.chat.SessionKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `待修复问题.md` 第 2 条的验收判据：窗口在前台且选中的就是这个会话、或者卡片已经
 * 渲染在眼前，才算用户正看着；其余一律提醒。
 *
 * 曾经比过 profile，结果「正在看的会话」因为 null / "default" 两种写法被误判成别的会话，
 * 卡片和系统通知一起弹 —— 现在只比全局唯一的 sessionId，并用 onScreen 兜底。
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
                selectedSessionId = "s2",
            ),
        )
    }

    @Test
    fun `same session id wins even if profile differs`() {
        // profile 只是分组，sessionId 全局唯一 —— 拿 profile 再比一次只会制造误判。
        assertFalse(
            shouldRemindForSession(
                target = SessionKey(profile = "work", sessionId = "s1"),
                foreground = true,
                selectedSessionId = "s1",
            ),
        )
    }

    @Test
    fun `card on screen is never reminded`() {
        // 卡片已经渲染在眼前：不管 id 对不对得上都不弹系统通知（卡片 + toast 双响就是这条漏了）。
        assertFalse(
            shouldRemindForSession(
                target = other,
                foreground = true,
                selectedSessionId = "s1",
                onScreen = true,
            ),
        )
        assertFalse(
            shouldRemindForSession(
                target = null,
                foreground = true,
                selectedSessionId = "s1",
                onScreen = true,
            ),
        )
    }

    @Test
    fun `background is always reminded`() {
        assertTrue(
            shouldRemindForSession(
                target = mine,
                foreground = false,
                selectedSessionId = "s1",
            ),
        )
        // 窗口在后台时，连 onScreen 也不该压掉提醒：用户看不到那张卡片。
        assertTrue(
            shouldRemindForSession(
                target = mine,
                foreground = false,
                selectedSessionId = "s1",
                onScreen = true,
            ),
        )
    }

    @Test
    fun `unknown session is reminded`() {
        assertTrue(
            shouldRemindForSession(
                target = null,
                foreground = true,
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
