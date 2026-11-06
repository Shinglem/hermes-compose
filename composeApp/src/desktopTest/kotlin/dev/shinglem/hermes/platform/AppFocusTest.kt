package dev.shinglem.hermes.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `AppFocus` 的语义：**界面层推下来的主窗口焦点是权威值**。
 *
 * 这条锁住一次真实事故：平台层原来用 `Window.getWindows().any { it.isVisible && it.isFocused }`
 * 推断前台，而 `isFocused` 是「进程内焦点」—— 任意辅助窗口（tooltip / 弹层）持有它就会让
 * 判定为真，于是用户切到后台后程序仍以为「他在看我」，审批提醒被整批跳过
 * （实测 3 个审批只弹 1 个）。所以推送值必须能覆盖推断，且两个方向都要准。
 */
class AppFocusTest {

    @AfterTest
    fun restoreForeground() {
        // 全局单例：别把"后台"状态漏给同进程的其他测试。
        AppFocus.update(true)
    }

    @Test
    fun `pushed background wins`() {
        AppFocus.update(false)
        assertFalse(AppFocus.isForeground)
    }

    @Test
    fun `pushed foreground wins`() {
        AppFocus.update(true)
        assertTrue(AppFocus.isForeground)
    }

    @Test
    fun `pushed value follows transitions`() {
        AppFocus.update(true)
        assertTrue(AppFocus.isForeground)
        AppFocus.update(false)
        assertFalse(AppFocus.isForeground)
        AppFocus.update(true)
        assertTrue(AppFocus.isForeground)
    }
}
