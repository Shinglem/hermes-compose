package dev.shinglem.hermes.component.subagent

import dev.shinglem.hermes.core.contract.SubagentListDto
import dev.shinglem.hermes.core.contract.SubagentTailDto
import dev.shinglem.hermes.core.transport.GatewayEvent
import kotlinx.coroutines.flow.Flow

/**
 * 子代理面板的数据入口（S5 模板）。
 *
 * **事件保持 `GatewayEvent` 原样**：把它归一化成自定义 sealed 类型会让
 * "字段改名后静默不更新"更难查（原有的事件解析逻辑已经很细：累加 tail、
 * 按 depth 排序、status 映射）。这一轮只换状态层，不动事件语义。
 */
interface SubagentGateway {
    /** 只含 `subagent.*` 的事件流（Gateway 侧已 filter）。 */
    val events: Flow<GatewayEvent>

    suspend fun list(sessionId: String, profile: String?): SubagentListDto

    suspend fun tail(sessionId: String, profile: String?, subagentId: String): SubagentTailDto

    /** @return 后端回报的状态字符串（`steering` / `queued` 等）。 */
    suspend fun steer(sessionId: String, profile: String?, subagentId: String, text: String): String?

    suspend fun interrupt(sessionId: String, profile: String?, subagentId: String)

    /** 当前会话的运行时（没活会话时返回 null）。 */
    fun runtime(): RuntimeRef?
}

/** 当前会话的 profile + runtimeId。 */
data class RuntimeRef(val profile: String?, val sessionId: String)