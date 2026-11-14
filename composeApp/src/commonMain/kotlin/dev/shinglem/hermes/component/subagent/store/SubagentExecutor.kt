package dev.shinglem.hermes.component.subagent.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.subagent.SubagentGateway
import dev.shinglem.hermes.component.subagent.SubagentIntent
import dev.shinglem.hermes.component.subagent.SubagentItem
import dev.shinglem.hermes.component.subagent.SubagentResult
import dev.shinglem.hermes.component.subagent.SubagentState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.transport.GatewayEvent
import dev.shinglem.hermes.core.transport.GatewayEventTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.CoroutineContext

/**
 * 子代理面板的执行器。
 *
 * ⚠️ 事件处理（[handleEvent]）是**逐行照搬** `SubagentViewModel` 的：
 * 累加 tail、按 depth 排序、status 映射、sessionId 过滤 —— 这几条都有"看起来能简化、
 * 一改就丢消息"的陷阱。这一轮只换状态层，不动事件语义。
 */
internal class SubagentExecutor(
    private val gateway: SubagentGateway,
    executorContext: CoroutineContext,
) : CoroutineExecutor<SubagentIntent, Nothing, SubagentState, SubagentResult, Nothing>(executorContext) {

    private var subscribed = false

    /** 懒订阅：MVIKotlin 的 Executor 在构造期不能 dispatch。 */
    private fun subscribeOnce() {
        if (subscribed) return
        subscribed = true
        gateway.events
            .onEach { event -> handleEvent(event) }
            .launchIn(scope)
    }

    override fun executeIntent(intent: SubagentIntent) {
        when (intent) {
            SubagentIntent.Observe -> subscribeOnce()
            SubagentIntent.Open -> {
                dispatch(SubagentResult.Opened(true))
                // 打开面板时自动拉一次：迁移前的 UI 用 LaunchedEffect(open) 做，等价但更容易漏。
                refresh()
            }
            SubagentIntent.Close -> dispatch(SubagentResult.Opened(false))
            SubagentIntent.Refresh -> refresh()
            is SubagentIntent.LoadTail -> loadTail(intent.subagentId)
            is SubagentIntent.Steer -> steer(intent.subagentId, intent.text)
            is SubagentIntent.Interrupt -> interrupt(intent.subagentId)
            SubagentIntent.DismissNotice -> dispatch(SubagentResult.Noticed(null))
        }
    }

    private fun refresh() {
        val ctx = gateway.runtime()
        if (ctx == null) {
            dispatch(SubagentResult.Failed(IllegalStateException("no runtime").asAppError("subagent.list"), unavailable = true))
            return
        }
        dispatch(SubagentResult.Loading)
        scope.launch {
            try {
                val dto = gateway.list(ctx.sessionId, ctx.profile)
                dispatch(
                    SubagentResult.Loaded(
                        dto.subagents.map {
                            SubagentItem(
                                id = it.subagentId,
                                parentId = it.parentId,
                                depth = it.depth ?: 0,
                                goal = it.goal,
                                status = it.status,
                                toolCount = it.toolCount ?: 0,
                                lastTool = it.lastTool,
                                model = it.model,
                                acceptingSteer = it.acceptingSteer ?: true,
                            )
                        },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(SubagentResult.Failed(throwable.asAppError("subagent.list"), unavailable = false))
            }
        }
    }

    private fun loadTail(subagentId: String) {
        val ctx = gateway.runtime() ?: return
        scope.launch {
            try {
                val dto = gateway.tail(ctx.sessionId, ctx.profile, subagentId)
                if (!dto.available) {
                    dispatch(SubagentResult.Noticed("这个子代理没有可读取的输出"))
                    return@launch
                }
                dispatch(SubagentResult.TailLoaded(subagentId, dto.text))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(SubagentResult.Noticed("读取子代理输出失败"))
            }
        }
    }

    private fun steer(subagentId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val ctx = gateway.runtime() ?: return
        if (state().steeringId != null) return
        dispatch(SubagentResult.Steering(subagentId))
        dispatch(SubagentResult.Noticed(null))
        scope.launch {
            try {
                val status = gateway.steer(ctx.sessionId, ctx.profile, subagentId, trimmed)
                dispatch(SubagentResult.Steering(null))
                dispatch(SubagentResult.Noticed(if (status == "steering") "已发送指令" else "已排队"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(SubagentResult.Steering(null))
                dispatch(SubagentResult.Noticed("发送指令失败"))
            }
        }
    }

    private fun interrupt(subagentId: String) {
        val ctx = gateway.runtime() ?: return
        scope.launch {
            try {
                gateway.interrupt(ctx.sessionId, ctx.profile, subagentId)
                dispatch(SubagentResult.Noticed("已请求中断"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(SubagentResult.Noticed("中断失败"))
            }
        }
    }

    /** 逐行照搬 `SubagentViewModel.handleEvent`（见类注释）。 */
    private fun handleEvent(event: GatewayEvent) {
        val ctx = gateway.runtime() ?: return
        if (event.sessionId != null && event.sessionId != ctx.sessionId) return
        val payload = event.payload as? JsonObject ?: return
        val id = payload.stringOrNull("subagent_id")?.takeIf { it.isNotBlank() } ?: return

        val current = state()
        val existing = current.entries.firstOrNull { it.id == id }
        val base = existing ?: SubagentItem(
            id = id,
            parentId = payload.stringOrNull("parent_id"),
            depth = (payload["depth"] as? JsonPrimitive)?.intOrNull ?: 0,
            goal = payload.stringOrNull("goal").orEmpty(),
            model = payload.stringOrNull("model"),
        )
        val toolCount = (payload["tool_count"] as? JsonPrimitive)?.intOrNull ?: base.toolCount
        val lastTool = payload.stringOrNull("tool_name") ?: payload.stringOrNull("tool_preview")
            ?: base.lastTool
        val appended = when (event.type) {
            GatewayEventTypes.SUBAGENT_THINKING,
            GatewayEventTypes.SUBAGENT_START,
            -> payload.stringOrNull("text").orEmpty()

            GatewayEventTypes.SUBAGENT_TOOL -> payload.stringOrNull("tool_preview")
                ?.let { "[$it]\n" }.orEmpty()

            else -> ""
        }
        val updated = base.copy(
            status = statusOf(event.type) ?: base.status,
            toolCount = toolCount,
            lastTool = lastTool,
            acceptingSteer = payload.stringOrNull("accepting_steer") != "false",
            tail = (base.tail + appended).takeLast(TAIL_LIMIT),
        )
        val others = current.entries.filterNot { it.id == id }
        val merged = (others + updated).sortedBy { it.depth }
        // 事件不走 Reducer 的常规通道：它是对现有列表的就地更新。
        // 用 `Loaded` 表达"整个列表换掉"，语义正确且能让 Reducer 保持纯函数。
        dispatch(SubagentResult.Loaded(merged))
    }

    private fun statusOf(type: String): String? = when (type) {
        GatewayEventTypes.SUBAGENT_SPAWN_REQUESTED,
        GatewayEventTypes.SUBAGENT_START,
        -> SubagentState.STATUS_RUNNING

        GatewayEventTypes.SUBAGENT_COMPLETE -> STATUS_DONE
        else -> null
    }

    private companion object {
        const val TAIL_LIMIT = 16 * 1024
        const val STATUS_DONE = "done"
    }
}

/** 与 `ChatRepository` 里的同名私有函数一致（那边是 private，无法复用）。 */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content