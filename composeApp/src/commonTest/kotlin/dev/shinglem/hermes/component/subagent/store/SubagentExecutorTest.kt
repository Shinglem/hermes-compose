package dev.shinglem.hermes.component.subagent.store

import dev.shinglem.hermes.component.subagent.RuntimeRef
import dev.shinglem.hermes.component.subagent.SubagentGateway
import dev.shinglem.hermes.component.subagent.SubagentIntent
import dev.shinglem.hermes.component.subagent.SubagentState
import dev.shinglem.hermes.core.contract.SubagentDto
import dev.shinglem.hermes.core.contract.SubagentListDto
import dev.shinglem.hermes.core.contract.SubagentTailDto
import dev.shinglem.hermes.core.transport.GatewayEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S8 回归：`SubagentExecutor.refresh()` **不能丢掉已拉到的输出**。
 *
 * 迁移前是 `dto.subagents.map { it.toEntry(tail = current.tailOf(it.subagentId)) }`；
 * 新实现一度直接 `SubagentItem(...)` 重建列表，`tail` 取默认空串 —— 而
 * `SubagentIntent.Open` 内部就会调 `refresh()`，于是每开一次面板 / 点一次刷新，
 * 用户已经读到的输出都被清空，得重新点「读取输出」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentExecutorTest {

    private class FakeGateway(
        var subagents: List<SubagentDto> = emptyList(),
        var tailText: String = "",
    ) : SubagentGateway {
        override val events: Flow<GatewayEvent> = emptyFlow()
        var runtimeRef: RuntimeRef? = RuntimeRef("default", "rt-1")

        override suspend fun list(sessionId: String, profile: String?): SubagentListDto =
            SubagentListDto(subagents)

        override suspend fun tail(sessionId: String, profile: String?, subagentId: String): SubagentTailDto =
            SubagentTailDto(subagentId = subagentId, available = true, text = tailText)

        override suspend fun steer(sessionId: String, profile: String?, subagentId: String, text: String): String? =
            "steering"

        override suspend fun interrupt(sessionId: String, profile: String?, subagentId: String) = Unit

        override fun runtime(): RuntimeRef? = runtimeRef
    }

    private fun subagent(id: String) = SubagentDto(
        subagentId = id,
        parentId = null,
        depth = 0,
        goal = "g-$id",
        status = "running",
        toolCount = 1,
        lastTool = "read",
        model = "m",
        acceptingSteer = true,
    )

    private fun store(
        gateway: SubagentGateway,
        scheduler: TestCoroutineScheduler,
    ) = SubagentStoreFactory.create(
        gateway = gateway,
        executorContext = UnconfinedTestDispatcher(scheduler),
    )

    @Test
    fun `刷新不会清掉已读到的输出`() = runTest {
        val gateway = FakeGateway(
            subagents = listOf(subagent("a")),
            tailText = "line-1\nline-2\n",
        )
        val store = store(gateway, testScheduler)
        try {
            store.accept(SubagentIntent.Refresh)
            store.accept(SubagentIntent.LoadTail("a"))
            assertEquals("line-1\nline-2\n", store.state.entries.single().tail, "前置条件：tail 已拉到")

            // 面板关闭再打开 / 点刷新都会走 refresh。
            store.accept(SubagentIntent.Refresh)

            assertEquals(
                "line-1\nline-2\n",
                store.state.entries.single().tail,
                "refresh 重建列表时必须按 id 回填 tail，否则用户每开一次面板都要重读输出",
            )
        } finally {
            store.dispose()
        }
    }

    @Test
    fun `没有 runtime 时标记不可用`() = runTest {
        val gateway = FakeGateway().apply { runtimeRef = null }
        val store = store(gateway, testScheduler)
        try {
            store.accept(SubagentIntent.Refresh)
            assertTrue(store.state.unavailable, "没有 runtime 应显示不可用，而不是空列表")
            assertTrue(!store.state.loading, "不可用不该停在 loading")
        } finally {
            store.dispose()
        }
    }

    @Test
    fun `刷新拿到的字段完整进 state`() = runTest {
        val gateway = FakeGateway(subagents = listOf(subagent("a"), subagent("b")))
        val store = store(gateway, testScheduler)
        try {
            store.accept(SubagentIntent.Refresh)
            val state: SubagentState = store.state
            assertEquals(2, state.entries.size)
            assertEquals(2, state.running, "两条都是 running")
            assertEquals("g-a", state.entries.first().goal)
        } finally {
            store.dispose()
        }
    }
}
