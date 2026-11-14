package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.result.Loadable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * S8 · 切走会话的状态快照（原 `ChatViewModel.savedStates` 一张裸 map + 上限 12 的魔法数）。
 *
 * 判据来自一个真实缺陷：切走时只标记 dirty、等切回补拉**不够** —— 切回那一刻那轮
 * 往往还在跑（审批就是在等它），`isSessionLive` 为真就跳过补拉，而切走期间的
 * `message.delta` 早已被丢弃，界面永久停在切走前那一帧（用户实测复现过两次）。
 */
class SessionSnapshotCacheTest {

    private val now = Instant.fromEpochSeconds(1_790_000_000)

    private fun key(id: String) = SessionKey(null, id)

    private fun state(id: String) = ChatState(key = key(id), load = Loadable.Ready(emptyList()))

    @Test
    fun `存取与丢弃`() {
        val cache = SessionSnapshotCache()
        cache.put(key("a"), state("a"))

        assertNotNull(cache[key("a")])
        assertTrue(cache.containsKey(key("a")))
        assertFalse(cache.containsKey(key("b")))

        assertEquals(key("a"), cache.remove(key("a"))?.key)
        assertNull(cache[key("a")], "remove 之后应当真的没有")
    }

    @Test
    fun `discard 只丢不返回`() {
        val cache = SessionSnapshotCache()
        cache.put(key("a"), state("a"))
        cache.discard(key("a"))
        assertNull(cache[key("a")])
    }

    /**
     * 上限 12：每份快照含完整消息列表，不设上限的话开着二十个会话就是二十份常驻。
     * 淘汰顺序按插入序（最早的那份先走）。
     */
    @Test
    fun `超过上限淘汰最早的一份`() {
        val cache = SessionSnapshotCache()
        repeat(13) { i -> cache.put(key("s$i"), state("s$i")) }

        assertEquals(12, cache.size)
        assertNull(cache[key("s0")], "最早插入的那份应当被淘汰")
        assertNotNull(cache[key("s12")], "最新的那份必须还在")
    }

    @Test
    fun `hasStreaming 反映该会话是否还在流式`() {
        val cache = SessionSnapshotCache()
        cache.put(key("a"), state("a"))
        assertFalse(cache.hasStreaming(key("a")))

        cache.track(key("a"), ChatStreamEvent.TurnStarted) { "$it#0" }
        assertTrue(cache.hasStreaming(key("a")))
    }

    /**
     * 没有快照时 `track` 必须忽略：这一轮不曾在本地跑过，凭空造一个"半截流式"状态会让
     * 切回时把它当成有内容，从而跳过补拉。
     */
    @Test
    fun `没有快照时 track 是空操作`() {
        val cache = SessionSnapshotCache()
        cache.track(key("ghost"), ChatStreamEvent.Delta("x")) { "$it#0" }
        assertNull(cache[key("ghost")])
        assertEquals(0, cache.size)
    }

    @Test
    fun `track 把增量累积进快照`() {
        val cache = SessionSnapshotCache()
        cache.put(key("a"), state("a"))
        var seq = 0
        cache.track(key("a"), ChatStreamEvent.TurnStarted) { "$it#${seq++}" }
        cache.track(key("a"), ChatStreamEvent.Delta("你")) { "$it#${seq++}" }
        cache.track(key("a"), ChatStreamEvent.Delta("好")) { "$it#${seq++}" }

        val streaming = assertNotNull(cache[key("a")]?.streaming)
        assertEquals("你好", streaming.segments.filterIsInstance<StreamSegment.Answer>().single().text)
    }

    @Test
    fun `updateAll 变换每份快照`() {
        val cache = SessionSnapshotCache()
        cache.put(key("a"), state("a"))
        cache.put(key("b"), state("b"))

        cache.updateAll { it.copy(stopping = true) }

        assertTrue(cache[key("a")]?.stopping == true)
        assertTrue(cache[key("b")]?.stopping == true)
    }
}
