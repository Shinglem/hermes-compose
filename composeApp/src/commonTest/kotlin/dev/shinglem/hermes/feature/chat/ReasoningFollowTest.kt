package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningFollowTest {

    @Test
    fun `内容增长且跟随开着时请求补滚`() {
        val next = nextReasoningFollow(previousScrollValue = 0, scrollValue = 0, maxValue = 120, follow = true)
        assertTrue(next.requestScroll)
        assertTrue(next.follow)
    }

    @Test
    fun `用户上翻立刻交还滚动权`() {
        val next = nextReasoningFollow(previousScrollValue = 120, scrollValue = 40, maxValue = 300, follow = true)
        assertFalse(next.follow, "value 变小说明用户在往上翻")
        assertFalse(next.requestScroll)
    }

    @Test
    fun `回到贴底恢复跟随`() {
        val next = nextReasoningFollow(previousScrollValue = 290, scrollValue = 295, maxValue = 300, follow = false)
        assertTrue(next.follow, "距底 5px 在余量内")
        assertFalse(next.requestScroll, "已经贴底，不必再滚")
    }

    @Test
    fun `跟随关着时内容增长不抢滚动`() {
        val next = nextReasoningFollow(previousScrollValue = 40, scrollValue = 40, maxValue = 300, follow = false)
        assertFalse(next.follow)
        assertFalse(next.requestScroll)
    }

    @Test
    fun `脱离贴底超过余量不算恢复`() {
        val next = nextReasoningFollow(previousScrollValue = 40, scrollValue = 40, maxValue = 300, follow = false)
        assertEquals(false, next.follow)
    }
}
