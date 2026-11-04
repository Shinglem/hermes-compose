package dev.shinglem.hermes.feature.session

import dev.shinglem.hermes.core.result.Loadable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 切 profile 时「选中会话」必须一起作废，否则会拿旧 id 去新 profile 读历史（404）。 */
class SessionListStateTest {

    private val selected = SessionListState(
        load = Loadable.Ready(emptyList()),
        profile = "butler",
        query = "abc",
        selectedId = "s-1",
        justCreatedId = "s-1",
    )

    @Test
    fun sameProfileKeepsSelection() {
        assertSame(selected, selected.switchedTo("butler"))
    }

    @Test
    fun otherProfileClearsSelection() {
        val next = selected.switchedTo("ops")
        assertEquals("ops", next.profile)
        assertNull(next.selectedId)
        assertNull(next.justCreatedId)
        // 搜索词等纯 UI 状态保留：切 profile 不该顺手清掉它们。
        assertEquals("abc", next.query)
    }

    @Test
    fun defaultProfileIsItsOwnBucket() {
        val next = selected.switchedTo(null)
        assertNull(next.profile)
        assertNull(next.selectedId)
    }
}
