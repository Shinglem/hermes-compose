package dev.shinglem.hermes.shell.window

import dev.shinglem.hermes.ui.notification.NOTIFICATION_CARD_WIDTH
import dev.shinglem.hermes.ui.notification.NOTIFICATION_SHADOW_MARGIN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 通知窗的屏幕几何（ADR-073：数据源从 AWT `GraphicsEnvironment` 换成 Tao `TaoMonitors`）。
 *
 * 这里锁的是**换算本身**：Tao 给的是物理像素 + `scaleFactor`，Compose 的窗口位置吃 dp。
 * 高 DPI 下漏掉 `/ scale` 会让通知跑到屏幕外 —— 这类错误在 100% 缩放下完全看不出来，
 * 只能靠数字断言。
 */
class NotificationGeometryTest {

    @Test
    fun `100% 缩放下贴在右下角并留边距`() {
        val area = WorkArea(left = 0f, top = 38f, width = 2560f, height = 1354f)
        val geometry = overlayGeometry(area)

        // 卡片宽 = 内容宽 + 两侧阴影留白
        val expectedWidth = NOTIFICATION_CARD_WIDTH.value + NOTIFICATION_SHADOW_MARGIN.value * 2
        assertEquals(expectedWidth, geometry.width)
        // 右边距 = 工作区右缘 − 卡片宽 − 边距
        assertEquals(area.left + area.width - expectedWidth - 16f, geometry.left)
        // 底边 = 工作区底缘 − 边距（不随卡片高度变，卡片从底往上叠）
        assertEquals(area.top + area.height - 16f, geometry.bottom)
    }

    @Test
    fun `高 DPI 下不超出工作区`() {
        // 逻辑上同一块屏：物理 2560x1440 / scale 2 → dp 1280x720
        val area = WorkArea(left = 0f, top = 19f, width = 1280f, height = 677f)
        val geometry = overlayGeometry(area)
        val expectedWidth = NOTIFICATION_CARD_WIDTH.value + NOTIFICATION_SHADOW_MARGIN.value * 2

        assertTrue(geometry.left >= area.left, "左缘不能跑到工作区外")
        assertTrue(
            geometry.left + geometry.width <= area.left + area.width,
            "右缘不能超出工作区：left=${geometry.left} width=${geometry.width} area=${area.left + area.width}",
        )
        assertEquals(expectedWidth, geometry.width, "dp 下卡片宽度与缩放无关")
    }

    @Test
    fun `非零原点的工作区也贴对右下角`() {
        // 副屏在主屏右侧：left 不是 0
        val area = WorkArea(left = 2560f, top = 0f, width = 1920f, height = 1040f)
        val geometry = overlayGeometry(area)
        val expectedWidth = NOTIFICATION_CARD_WIDTH.value + NOTIFICATION_SHADOW_MARGIN.value * 2
        assertEquals(2560f + 1920f - expectedWidth - 16f, geometry.left)
        assertEquals(1040f - 16f, geometry.bottom)
    }
}