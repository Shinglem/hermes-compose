package dev.shinglem.hermes.component.subagent.store

import dev.shinglem.hermes.component.subagent.SubagentItem
import dev.shinglem.hermes.component.subagent.SubagentResult
import dev.shinglem.hermes.component.subagent.SubagentState
import dev.shinglem.hermes.core.error.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 子代理面板的纯状态迁移（S8：这一域此前**零测试**）。
 *
 * 重点断的两条都是真实设计约束，不是形式：
 *
 * 1. **`unavailable` 与 `error` 互斥** —— "后端没有子代理能力"不该显示成红色错误横幅，
 *    否则用户会以为出故障了；反过来"这次请求失败"也不该被当成能力缺失。
 * 2. **`steeringId` 是单值** —— 同时只允许一个 steer 在途（Executor 里
 *    `if (state().steeringId != null) return` 就是靠它挡重复点击），所以它必须能被清回 null。
 */
class SubagentReducerTest {

    private val reducer = SubagentReducer()

    private fun reduce(state: SubagentState, msg: SubagentResult): SubagentState =
        reducer.run { state.reduce(msg) }

    private fun item(id: String, status: String? = null) =
        SubagentItem(id = id, goal = "g-$id", status = status)

    @Test
    fun `初始状态是空的且不加载`() {
        val state = SubagentState()
        assertFalse(state.open)
        assertFalse(state.loading)
        assertTrue(state.isEmpty)
        assertNull(state.error)
    }

    @Test
    fun `Opened 只改 open`() {
        assertTrue(reduce(SubagentState(), SubagentResult.Opened(true)).open)
        assertFalse(reduce(SubagentState(open = true), SubagentResult.Opened(false)).open)
    }

    @Test
    fun `Loading 清掉上一次的错误与 unavailable`() {
        val before = SubagentState(loading = false, error = AppError.Unknown("boom"), unavailable = true)
        val after = reduce(before, SubagentResult.Loading)
        assertTrue(after.loading)
        assertNull(after.error)
        assertFalse(after.unavailable, "重新加载应清掉「能力不可用」标记")
    }

    @Test
    fun `Loaded 落成列表并结束加载`() {
        val after = reduce(SubagentState(loading = true), SubagentResult.Loaded(listOf(item("a"), item("b"))))
        assertFalse(after.loading)
        assertEquals(2, after.entries.size)
        assertFalse(after.unavailable)
        assertNull(after.error)
    }

    /**
     * **关键**：`unavailable=true` 时不能同时挂 `error`。
     * 两个都显示的话界面会出现「没有这个能力」+ 红色错误横幅并列。
     */
    @Test
    fun `unavailable 时不显示错误横幅`() {
        val after = reduce(
            SubagentState(),
            SubagentResult.Failed(AppError.Unknown("no capability"), unavailable = true),
        )
        assertTrue(after.unavailable)
        assertNull(after.error, "能力缺失不该显示成红色错误")
        assertTrue(after.entries.isEmpty(), "能力不可用时列表要清空，避免留下上次的残影")
    }

    @Test
    fun `普通失败保留错误但要清 unavailable`() {
        val error = AppError.Unknown("timeout")
        val after = reduce(
            SubagentState(loading = true, entries = listOf(item("a")), unavailable = true),
            SubagentResult.Failed(error, unavailable = false),
        )
        assertEquals(error, after.error)
        assertFalse(after.unavailable)
        assertEquals(1, after.entries.size, "普通失败不该清列表 —— 上次的数据还能看")
        assertFalse(after.loading)
    }

    /** `TailLoaded` 只更新目标那条，其余原样。 */
    @Test
    fun `TailLoaded 只改目标条目`() {
        val before = SubagentState(entries = listOf(item("a"), item("b")))
        val after = reduce(before, SubagentResult.TailLoaded("b", "新的一段输出"))
        assertEquals("", after.entries[0].tail)
        assertEquals("新的一段输出", after.entries[1].tail)
        assertEquals(2, after.entries.size, "不该新增或丢失条目")
    }

    @Test
    fun `TailLoaded 目标不存在时是空操作`() {
        val before = SubagentState(entries = listOf(item("a")))
        assertEquals(before, reduce(before, SubagentResult.TailLoaded("nope", "x")))
    }

    @Test
    fun `Steering 置位与清回`() {
        val steering = reduce(SubagentState(), SubagentResult.Steering("a"))
        assertEquals("a", steering.steeringId)
        // Executor 靠非 null 挡重复点击，所以必须能被清回 null。
        assertNull(reduce(steering, SubagentResult.Steering(null)).steeringId)
    }

    @Test
    fun `Noticed 覆盖与清空提示`() {
        assertEquals("已排队", reduce(SubagentState(), SubagentResult.Noticed("已排队")).notice)
        assertNull(reduce(SubagentState(notice = "x"), SubagentResult.Noticed(null)).notice)
    }

    /** `running` 计数只认 `STATUS_RUNNING`，用于面板标题上的"N 个运行中"。 */
    @Test
    fun `running 只数 running 状态的条目`() {
        val state = SubagentState(
            entries = listOf(
                item("a", SubagentState.STATUS_RUNNING),
                item("b", SubagentState.STATUS_RUNNING),
                item("c", "done"),
                item("d", null),
            ),
        )
        assertEquals(2, state.running)
    }
}
