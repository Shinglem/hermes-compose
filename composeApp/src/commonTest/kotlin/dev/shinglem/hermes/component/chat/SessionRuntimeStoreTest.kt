package dev.shinglem.hermes.component.chat

import dev.shinglem.hermes.feature.chat.SessionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S4 · `SessionRuntimeStore`（8 个平行 map 收敛成的值对象 + 反向索引）。
 *
 * 重点断三条 —— 它们都是迁移前**手写 map 时踩过**的问题：
 * 1. **反向索引必须随 runtimeId 一起更新**：换 runtimeId（重连后必然发生）时旧 id
 *    必须摘掉，否则旧 id 会一直指向这个会话，事件被错误归属；
 * 2. **重连只清"连接作用域"的字段**（fence / revision / lastEventAt），
 *    `key` / `runtimeId` / `cwd` / `dirty` 必须留着；
 * 3. **`dirty` 不能被重连清掉**：那是"切走期间有新内容"的事实，清了就永远不补拉。
 */
class SessionRuntimeStoreTest {

    private fun key(sessionId: String, profile: String? = "default") = SessionKey(profile, sessionId)

    private fun runtime(
        sessionId: String,
        runtimeId: String = "rt-$sessionId",
        fence: Int? = 1,
        cwd: String? = null,
        dirty: Boolean = false,
    ) = SessionRuntime(
        key = key(sessionId),
        runtimeId = runtimeId,
        fence = fence,
        revision = 2,
        cwd = cwd,
        lastEventAt = 100L,
        dirty = dirty,
    )

    @Test
    fun `登记后可按 key 与 runtimeId 双向查到`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1"))

        assertEquals("rt1", store[key("s1")]?.runtimeId)
        assertEquals(key("s1"), store.byRuntimeId("rt1"))
        assertEquals(1, store.size)
    }

    /**
     * 换 runtimeId 时旧的反向索引必须摘掉。
     *
     * 这是迁移前 5 个平行 map 最容易漏的一条：`runtimeIds[key] = new` 之后，
     * 反向查（当时是 O(n) 遍历）会同时命中新旧两个值，事件归属取决于遍历顺序。
     */
    @Test
    fun `换 runtimeId 后旧 id 不再指向该会话`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt-old"))
        store.put(runtime("s1", runtimeId = "rt-new"))

        assertNull(store.byRuntimeId("rt-old"), "旧 runtimeId 必须摘掉，否则事件会被错误归属")
        assertEquals(key("s1"), store.byRuntimeId("rt-new"))
        assertEquals(1, store.size, "同一会话不该占两条")
    }

    @Test
    fun `remove 同时清反向索引`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1"))
        store.remove(key("s1"))

        assertNull(store[key("s1")])
        assertNull(store.byRuntimeId("rt1"))
        assertEquals(0, store.size)
    }

    @Test
    fun `update 不改 runtimeId`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1"))
        store.update(key("s1")) { it.copy(fence = 42) }

        assertEquals(42, store[key("s1")]?.fence)
        assertEquals(key("s1"), store.byRuntimeId("rt1"), "update 不该动反向索引")
    }

    @Test
    fun `更新不存在的 key 是空操作`() {
        val store = SessionRuntimeStore()
        store.update(key("nope")) { it.copy(fence = 9) }
        assertEquals(0, store.size)
    }

    /**
     * 重连：只清 fence / revision / lastEventAt，保留 key / runtimeId / cwd / dirty。
     *
     * 迁移前这四行是 `runtimeIds.clear() + generations.clear() + revisions.clear() +
     * lastEventAt.clear()` —— 其中 `runtimeIds.clear()` 其实是错的（会话还开着，
     * 清了之后 `open()` 里的 `runtimeIds[key]?.let { refreshPendingApprovals(...) }` 就不生效）。
     * 这里把正确语义钉住。
     */
    @Test
    fun `重连只清连接作用域字段`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1", fence = 7, cwd = "/tmp/x", dirty = true))

        store.clearConnectionScoped()

        val after = store[key("s1")]
        assertNotNull(after, "重连不该丢掉会话本身")
        assertEquals("rt1", after.runtimeId, "runtimeId 要留着：后面 resume 会刷新它")
        assertEquals("/tmp/x", after.cwd, "cwd 与连接无关")
        assertTrue(after.dirty, "dirty 是内容事实，不能被重连清掉，否则永远不补拉")
        assertNull(after.fence, "fence 绑在旧连接上，必须清")
        assertNull(after.revision)
        assertEquals(0L, after.lastEventAt)
    }

    @Test
    fun `clear 清掉一切`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1"))
        store.clear()

        assertEquals(0, store.size)
        assertNull(store.byRuntimeId("rt1"))
    }

    @Test
    fun `byRuntimeId 忽略空值`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1"))
        assertNull(store.byRuntimeId(null))
        assertNull(store.byRuntimeId(""))
        assertNull(store.byRuntimeId("   "))
    }

    @Test
    fun `markDirty 与 touch 只改对应字段`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", fence = 3))

        store.markDirty(key("s1"))
        assertTrue(store[key("s1")]?.dirty == true)
        assertEquals(3, store[key("s1")]?.fence, "markDirty 不该碰 fence")

        store.touch(key("s1"), atMillis = 999L)
        assertEquals(999L, store[key("s1")]?.lastEventAt)
    }

    @Test
    fun `多会话互不干扰`() {
        val store = SessionRuntimeStore()
        store.put(runtime("s1", runtimeId = "rt1"))
        store.put(runtime("s2", runtimeId = "rt2"))
        store.markDirty(key("s1"))

        assertTrue(store[key("s1")]?.dirty == true)
        assertTrue(store[key("s2")]?.dirty == false, "另一个会话不该被连带标脏")
        assertEquals(2, store.size)
    }
}
