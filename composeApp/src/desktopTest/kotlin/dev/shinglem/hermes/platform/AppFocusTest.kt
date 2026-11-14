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
 *
 * S6 收尾又加一条：兜底的**数据源**必须在 Tao 进程里有效。旧兜底查
 * `java.awt.Window.getWindows()`，而 Tao 主窗口不是 AWT 窗口 → 集合恒空 → 恒判"不在前台"
 * → 每条通知都弹。现在兜底改成 Nucleus 的窗口焦点探针，失败方向必须是"假定在前台"。
 */
class AppFocusTest {

    @AfterTest
    fun restoreForeground() {
        // 全局单例：别把"后台"状态漏给同进程的其他测试。
        AppFocus.attachProbe(null)
        AppFocus.clearPushed()
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

    @Test
    fun `probe is consulted when no push happened`() {
        // 推送值缺席时才走兜底：Tao 迁完之后这条路径是新实现（旧的是 AWT 窗口集合）。
        // 用 probe 的返回值证明它真的被读了。
        AppFocus.clearPushed()
        AppFocus.attachProbe { false }
        assertFalse(AppFocus.isForeground, "未推送时应回落到 probe 的结果")

        AppFocus.attachProbe { true }
        assertTrue(AppFocus.isForeground)
    }

    @Test
    fun `push overrides probe`() {
        AppFocus.attachProbe { false }
        AppFocus.update(true)
        assertTrue(AppFocus.isForeground, "有推送值时以推送为准")
        AppFocus.clearPushed()
        assertFalse(AppFocus.isForeground, "清掉推送后回到 probe")
    }

    @Test
    fun `probe failure falls back to foreground`() {
        // 探针抛异常时不能把"未知"当成"用户不在看" —— 那会让每条通知都弹。
        AppFocus.attachProbe { error("boom") }
        assertTrue(AppFocus.isForeground)
    }
}
