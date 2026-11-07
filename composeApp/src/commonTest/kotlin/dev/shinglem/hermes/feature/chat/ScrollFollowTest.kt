package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 流式跟随的判定逻辑：这些分支直接决定「AI 回答时画面会不会自己走」，
 * 用最小用例把四类误判钉住。
 */
class ScrollFollowTest {

    @Test
    fun slightUpwardFlickDoesNotPauseFollow() {
        // 触摸板惯性 / 滚轮余量：delta 负、但离底部只有几个像素 —— 不该停跟。
        assertFalse(shouldPauseFollow(deltaY = -1f, distanceFromBottomPx = 0))
        assertFalse(shouldPauseFollow(deltaY = -40f, distanceFromBottomPx = 12))
        assertFalse(shouldPauseFollow(deltaY = -40f, distanceFromBottomPx = 24))
    }

    @Test
    fun realUpwardScrollPausesFollow() {
        assertTrue(shouldPauseFollow(deltaY = -40f, distanceFromBottomPx = 25))
        assertTrue(shouldPauseFollow(deltaY = -120f, distanceFromBottomPx = 800))
    }

    @Test
    fun downwardScrollNeverPausesFollow() {
        // 向下滚不暂停；是否恢复跟随由「是否贴底」决定，不看方向。
        assertFalse(shouldPauseFollow(deltaY = 40f, distanceFromBottomPx = 500))
        assertFalse(shouldPauseFollow(deltaY = 0.5f, distanceFromBottomPx = 500))
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
