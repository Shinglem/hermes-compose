package dev.shinglem.hermes.component.updater

import dev.shinglem.hermes.core.contract.BackendUpdateCheckDto
import dev.shinglem.hermes.core.error.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

/**
 * `UpdaterComponent` 的状态机（S5 模板验收）。
 *
 * 用假 Gateway 而不是真 `RestClient`：这一层要验的是"状态怎么变"，
 * 不是"HTTP 怎么发"。S2 定的模板就是为了让 7 个域都能这样测。
 */
class UpdaterComponentTest {

    private class FakeGateway : UpdaterGateway {
        var result: BackendUpdateCheckDto? = null
        var failure: Throwable? = null
        var calls = 0
        var lastForce: Boolean? = null
        /** 非空时挂起，用来观察"进行中"的中间状态。 */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun checkBackendUpdate(force: Boolean): BackendUpdateCheckDto {
            calls++
            lastForce = force
            gate?.await()
            failure?.let { throw it }
            return result ?: BackendUpdateCheckDto(updateAvailable = false)
        }
    }

    private fun dto(available: Boolean) = BackendUpdateCheckDto(updateAvailable = available)

    @Test
    fun `初始状态是空闲`() {
        val state = UpdaterState()
        assertFalse(state.checking)
        assertNull(state.result)
        assertNull(state.error)
        assertFalse(state.updateAvailable)
    }

    @Test
    fun `Reducer 成功后 checking 归零且带上结果`() {
        val reducer = dev.shinglem.hermes.component.updater.store.UpdaterReducer()
        val afterStart = reducer.run { UpdaterState().reduce(UpdaterResult.Started(force = true)) }
        assertTrue(afterStart.checking)
        assertNull(afterStart.error)

        val afterLoad = reducer.run { afterStart.reduce(UpdaterResult.Loaded(dto(true))) }
        assertFalse(afterLoad.checking)
        assertTrue(afterLoad.updateAvailable)
        assertNull(afterLoad.error)
    }

    @Test
    fun `Reducer 失败时保留旧结果`() {
        // 后端挂了不该让已经拿到的版本信息消失（迁移前就是这个语义）。
        val reducer = dev.shinglem.hermes.component.updater.store.UpdaterReducer()
        val withResult = UpdaterState(result = dto(true))
        val failed = reducer.run { withResult.reduce(UpdaterResult.Failed(AppError.Timeout("x"))) }

        assertFalse(failed.checking)
        assertNotNull(failed.error)
        assertNotNull(failed.result, "失败不应清掉旧结果")
        assertTrue(failed.updateAvailable, "旧结果里的 updateAvailable 仍然有效")
    }

    @Test
    fun `Reducer 清错误但不动结果`() {
        val reducer = dev.shinglem.hermes.component.updater.store.UpdaterReducer()
        val errored = UpdaterState(result = dto(true), error = AppError.Timeout("x"))
        val cleared = reducer.run { errored.reduce(UpdaterResult.Cleared) }
        assertNull(cleared.error)
        assertNotNull(cleared.result)
    }

    // ---- 经过 Store 的端到端（Executor 不能单独 new：MVIKotlin 要求由 Store 初始化）----

    @Test
    fun `Check 走完 store 后 checking 归零且带上结果`() = runTest {
        val gateway = FakeGateway().apply { result = dto(true) }
        val store = dev.shinglem.hermes.component.updater.store.UpdaterStoreFactory.create(
            gateway = gateway,
            executorContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        try {
            store.accept(UpdaterIntent.Check(force = false))
            assertEquals(1, gateway.calls)
            assertEquals(false, gateway.lastForce)
            val state = store.state
            assertFalse(state.checking)
            assertTrue(state.updateAvailable)
            assertNull(state.error)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun `失败被转成 AppError 且保留旧结果`() = runTest {
        val gateway = FakeGateway().apply {
            result = dto(true)
            failure = IllegalStateException("boom")
        }
        val store = dev.shinglem.hermes.component.updater.store.UpdaterStoreFactory.create(
            gateway = gateway,
            executorContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        try {
            store.accept(UpdaterIntent.Check())
            val state = store.state
            assertFalse(state.checking)
            assertNotNull(state.error, "失败必须落到 error，不能逃逸成崩溃")
            assertNull(state.result, "首次就失败时没有旧结果可留")
        } finally {
            store.dispose()
        }
    }

    @Test
    fun `已经在查时不重复请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply { this.gate = gate }
        val store = dev.shinglem.hermes.component.updater.store.UpdaterStoreFactory.create(
            gateway = gateway,
            executorContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        try {
            store.accept(UpdaterIntent.Check())
            assertTrue(store.state.checking, "第一次进入进行中")
            store.accept(UpdaterIntent.Check()) // 应当被 checking 挡住
            assertEquals(1, gateway.calls, "进行中不应再发一次请求")
            gate.complete(Unit)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun `DismissError 只清错误`() = runTest {
        val gateway = FakeGateway().apply { result = dto(false) }
        val store = dev.shinglem.hermes.component.updater.store.UpdaterStoreFactory.create(
            gateway = gateway,
            executorContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        try {
            store.accept(UpdaterIntent.Check())
            assertNotNull(store.state.result)
            store.accept(UpdaterIntent.DismissError)
            assertNull(store.state.error)
            assertNotNull(store.state.result, "清错误不该动结果")
        } finally {
            store.dispose()
        }
    }
}