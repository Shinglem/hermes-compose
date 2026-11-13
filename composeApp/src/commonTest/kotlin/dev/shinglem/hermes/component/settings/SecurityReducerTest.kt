package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.component.settings.store.SecurityReducer
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 · 安全页 Reducer（纯函数）。
 *
 * 这里断的是**行为等价于迁移前的 `SecurityViewModel`**，特别是三条曾经出过问题的：
 * 1. 刷新时不能把已有配置清空（否则表单闪一下空白）；
 * 2. 失败时保留旧值（后端连不上时用户仍要能看和改）；
 * 3. 换 profile 必须丢掉 edits（曾差点把 A 仓库的改动写进 B 仓库）。
 */
class SecurityReducerTest {

    private val reducer = SecurityReducer()

    private fun reduce(state: SecurityState, result: SecurityResult): SecurityState =
        reducer.run { state.reduce(result) }

    private fun config(value: Int): JsonObject = buildJsonObject { put("v", value) }

    @Test
    fun `首次加载从 Idle 进入 Loading`() {
        val state = reduce(SecurityState(), SecurityResult.Loading(stale = null))
        assertEquals(Loadable.Loading, state.config)
    }

    @Test
    fun `已有数据时刷新不退回 Loading`() {
        val loaded = SecurityState(config = Loadable.Ready(config(1)))
        val state = reduce(loaded, SecurityResult.Loading(stale = config(1)))
        assertTrue(state.config is Loadable.Ready, "刷新时把已有配置清掉了：${state.config}")
        assertEquals(1, state.config.valueOrNull?.let { it["v"].toString().toInt() })
    }

    @Test
    fun `加载成功替换配置并清掉错误`() {
        val failed = SecurityState(config = Loadable.Failed(AppError.Unknown("boom"), null), error = "旧错误")
        val state = reduce(failed, SecurityResult.Loaded(config(2), profile = null))
        assertTrue(state.config is Loadable.Ready)
        assertNull(state.error)
    }

    @Test
    fun `加载失败保留旧值`() {
        val loaded = SecurityState(config = Loadable.Ready(config(3)))
        val state = reduce(loaded, SecurityResult.Failed(AppError.Unknown("boom"), stale = config(3)))
        val failed = state.config
        assertTrue(failed is Loadable.Failed, "失败应保留为 Failed 以便 UI 显示横幅：$failed")
        assertNotNull(failed.stale, "失败时丢了旧值，用户就看不到最后读到的配置了")
    }

    @Test
    fun `换 profile 要丢掉上一份 edits`() {
        val state = SecurityState(
            config = Loadable.Ready(config(1)),
            profile = "alpha",
            edits = persistentMapOf("approval.mode" to "always"),
        )
        val next = reduce(state, SecurityResult.Loaded(config(9), profile = "beta"))
        assertEquals("beta", next.profile)
        assertFalse(next.dirty, "换 profile 后 edits 应清空，否则会写进错误的仓库")
    }

    @Test
    fun `同 profile 重新加载不动 edits`() {
        val state = SecurityState(
            profile = "alpha",
            edits = persistentMapOf("approval.mode" to "always"),
        )
        val next = reduce(state, SecurityResult.Loaded(config(9), profile = "alpha"))
        assertTrue(next.dirty, "同 profile 刷新不该丢用户正在编辑的内容")
    }

    @Test
    fun `编辑写入 edits 并清掉横幅`() {
        val state = SecurityState(error = "旧错误", notice = "旧提示")
        val next = reduce(state, SecurityResult.Edited("approval.mode", "always"))
        assertEquals("always", next.edits["approval.mode"])
        assertNull(next.error)
        assertNull(next.notice)
        assertTrue(next.dirty)
    }

    @Test
    fun `开始保存置忙`() {
        val next = reduce(SecurityState(edits = persistentMapOf("a" to "b")), SecurityResult.Saving)
        assertEquals("config", next.busyKey)
        assertTrue(next.saving)
    }

    @Test
    fun `保存成功清空 edits 并给出提示`() {
        val state = SecurityState(
            config = Loadable.Ready(config(1)),
            edits = persistentMapOf("a" to "b"),
            busyKey = "config",
        )
        val next = reduce(state, SecurityResult.EditsCleared(config(2)))
        assertFalse(next.dirty)
        assertNull(next.busyKey)
        assertEquals("已保存", next.notice)
        assertEquals(2, next.config.valueOrNull?.let { it["v"].toString().toInt() })
    }

    @Test
    fun `保存失败保留 edits 以便重试`() {
        val state = SecurityState(edits = persistentMapOf("a" to "b"), busyKey = "config")
        val next = reduce(state, SecurityResult.SaveFailed(AppError.Unknown("nope")))
        assertTrue(next.dirty, "保存失败后清空 edits 会让用户白填一遍")
        assertNull(next.busyKey)
        assertNotNull(next.error)
    }

    @Test
    fun `没有可保存的改动给出提示`() {
        val next = reduce(SecurityState(busyKey = "config"), SecurityResult.NothingToSave)
        assertNull(next.busyKey)
        assertNotNull(next.error)
    }

    @Test
    fun `清除横幅`() {
        val next = reduce(SecurityState(error = "e", notice = "n"), SecurityResult.BannersCleared)
        assertNull(next.error)
        assertNull(next.notice)
    }
}
