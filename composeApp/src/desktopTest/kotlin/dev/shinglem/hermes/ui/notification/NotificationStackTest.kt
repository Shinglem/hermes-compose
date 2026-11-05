package dev.shinglem.hermes.ui.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 堆叠位置计算。
 *
 * 抽成纯函数的原因：窗口位置只能靠肉眼看，而"叠歪了 / 互相压住 / 跑到屏幕外"这类错误
 * 在截图里不一定立刻发现。这里把判据写成数字。
 */
class NotificationStackTest {

    private val bottom = 1040f
    private val gap = 8f

    @Test
    fun `单条贴底`() {
        assertEquals(listOf(bottom - 176f), stackOffsets(listOf(176f), bottom, gap))
    }

    @Test
    fun `三条自下而上排列且互不重叠`() {
        val heights = listOf(176f, 176f, 176f)
        val offsets = stackOffsets(heights, bottom, gap)
        assertEquals(3, offsets.size)
        assertEquals(bottom - 176f, offsets[0])
        assertEquals(bottom - 176f - (176f + gap), offsets[1])
        assertEquals(bottom - 176f - 2 * (176f + gap), offsets[2])
        // 相邻两条之间正好留 gap，没有压住
        for (i in 0 until offsets.size - 1) {
            assertEquals(heights[i] + gap, offsets[i] - offsets[i + 1])
        }
    }

    @Test
    fun `高度不同时按实际高度累计`() {
        // 第一条是有按钮的高卡片，上面那条是纯提示
        val offsets = stackOffsets(listOf(176f, 128f), bottom, gap)
        assertEquals(bottom - 176f, offsets[0])
        // 上面那条 = 底边 − 自己高度 − 下面那条占掉的高度（含间距）
        assertEquals(bottom - 176f - gap - 128f, offsets[1])
    }

    @Test
    fun `空列表返回空`() {
        assertTrue(stackOffsets(emptyList(), bottom, gap).isEmpty())
    }
}
