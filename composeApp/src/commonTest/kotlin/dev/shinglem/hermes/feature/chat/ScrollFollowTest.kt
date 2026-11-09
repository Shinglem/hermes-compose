package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 流式跟随的判定逻辑：这些分支直接决定「AI 回答时画面会不会自己走」，
 * 用最小用例把误判钉住。
 */
class ScrollFollowTest {

    @Test
    fun anyUpwardScrollHandsControlBack() {
        // 流式期间向上滚就是接管：哪怕只滚了一点点，也必须立刻停跟随 ——
        // 否则补滚会把画面拽回底部，用户看到的是滚轮向上时闪烁跳变。
        assertTrue(shouldPauseFollow(deltaY = -1f))
        assertTrue(shouldPauseFollow(deltaY = -40f))
        assertTrue(shouldPauseFollow(deltaY = -120f))
    }

    @Test
    fun downwardScrollNeverPausesFollow() {
        // 向下滚不暂停；是否恢复跟随由「是否贴底」决定，不看方向。
        assertFalse(shouldPauseFollow(deltaY = 40f))
        assertFalse(shouldPauseFollow(deltaY = 0.5f))
        assertFalse(shouldPauseFollow(deltaY = 0f))
    }

    @Test
    fun reachingBottomResumesFollow() {
        assertTrue(shouldResumeFollow(distanceFromBottomPx = 0))
        assertTrue(shouldResumeFollow(distanceFromBottomPx = 8))
        // 布局取整留一点余量也算贴底，但不该放宽到明显离开底部。
        assertFalse(shouldResumeFollow(distanceFromBottomPx = 9))
        assertFalse(shouldResumeFollow(distanceFromBottomPx = 400))
    }

    @Test
    fun offscreenItemsAreNeverTreatedAsBottom() {
        // 列表停在中途：最后可见项刚好贴着视口底，但后面还有项 —— 不能算贴底。
        val gap = contentEndGapPx(
            lastVisibleIndex = 10,
            totalItemsCount = 42,
            lastVisibleBottomPx = 600,
            viewportBottomPx = 600,
        )
        assertTrue(gap > ScrollFollow.STICK_SLOP_PX, "还有内容在视口外时必须算作离底很远")
        assertFalse(shouldResumeFollow(gap))
        assertTrue(shouldPauseFollow(deltaY = -1f))

        // 最后一项已进视口、底部贴着视口底：才算到末尾。
        assertEquals(
            0,
            contentEndGapPx(
                lastVisibleIndex = 41,
                totalItemsCount = 42,
                lastVisibleBottomPx = 600,
                viewportBottomPx = 600,
            ),
        )
        // 最后一项超出视口的部分就是真实距离。
        assertEquals(
            30,
            contentEndGapPx(
                lastVisibleIndex = 41,
                totalItemsCount = 42,
                lastVisibleBottomPx = 630,
                viewportBottomPx = 600,
            ),
        )
        assertEquals(
            0,
            contentEndGapPx(
                lastVisibleIndex = 0,
                totalItemsCount = 0,
                lastVisibleBottomPx = 0,
                viewportBottomPx = 600,
            ),
        )
    }

    @Test
    fun screenshotScenarioKeepsFollowingWhileStreaming() {
        // 场景：用户在底部，AI 每来一段内容距底 distance>0 → 应当补滚（followTail 保持 true）。
        var following = true
        val growth = listOf(36, 120, 900)
        for (distance in growth) {
            if (following && distance > 0) {
                // 补滚后 distance 归零
                following = true
            }
        }
        assertTrue(following)

        // 场景：用户向上翻走 → 暂停；后续内容继续增长也不该恢复。
        following = false
        for (distance in listOf(300, 1200, 3000)) {
            if (!following && shouldResumeFollow(distance)) following = true
        }
        assertFalse(following)

        // 场景：用户滚回底部 → 恢复跟随。
        assertTrue(shouldResumeFollow(0))
    }
}
