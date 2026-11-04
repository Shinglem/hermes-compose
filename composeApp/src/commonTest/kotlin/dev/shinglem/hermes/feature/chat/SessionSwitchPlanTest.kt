package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `待修复问题.md` 第 3 条的判据表：切回会话时该恢复快照、用缓存，还是重读。
 *
 * 关键的一条是「stale 但**没有**快照」—— 不能以为恢复过了就直接把内容丢掉，
 * 要落到缓存 / 重读；反过来「有快照但不是 stale」也不该白等一次重读。
 */
class SessionSwitchPlanTest {

    @Test
    fun `already viewing a ready session is left alone`() {
        assertEquals(
            SessionSwitchPlan.KEEP,
            switchPlan(
                force = false,
                sameKeyReady = true,
                stale = true,
                cachedStreaming = false,
                hasSnapshot = true,
                hasCache = true,
            ),
        )
    }

    @Test
    fun `stale session restores its snapshot first`() {
        assertEquals(
            SessionSwitchPlan.RESTORE_SNAPSHOT,
            switchPlan(
                force = false,
                sameKeyReady = false,
                stale = true,
                cachedStreaming = false,
                hasSnapshot = true,
                hasCache = true,
            ),
        )
    }

    @Test
    fun `session that was streaming keeps its snapshot`() {
        assertEquals(
            SessionSwitchPlan.RESTORE_SNAPSHOT,
            switchPlan(
                force = false,
                sameKeyReady = false,
                stale = false,
                cachedStreaming = true,
                hasSnapshot = true,
                hasCache = true,
            ),
        )
    }

    @Test
    fun `stale without snapshot falls back to cache`() {
        assertEquals(
            SessionSwitchPlan.USE_CACHE,
            switchPlan(
                force = false,
                sameKeyReady = false,
                stale = true,
                cachedStreaming = false,
                hasSnapshot = false,
                hasCache = true,
            ),
        )
    }

    @Test
    fun `plain revisit uses cache`() {
        assertEquals(
            SessionSwitchPlan.USE_CACHE,
            switchPlan(
                force = false,
                sameKeyReady = false,
                stale = false,
                cachedStreaming = false,
                hasSnapshot = false,
                hasCache = true,
            ),
        )
    }

    @Test
    fun `first visit reloads`() {
        assertEquals(
            SessionSwitchPlan.RELOAD,
            switchPlan(
                force = false,
                sameKeyReady = false,
                stale = false,
                cachedStreaming = false,
                hasSnapshot = false,
                hasCache = false,
            ),
        )
    }

    /** 用户主动刷新（重试 / 下拉）必须真读，不能拿快照或缓存糊过去。 */
    @Test
    fun `force always reloads`() {
        assertEquals(
            SessionSwitchPlan.RELOAD,
            switchPlan(
                force = true,
                sameKeyReady = true,
                stale = true,
                cachedStreaming = true,
                hasSnapshot = true,
                hasCache = true,
            ),
        )
    }
}
