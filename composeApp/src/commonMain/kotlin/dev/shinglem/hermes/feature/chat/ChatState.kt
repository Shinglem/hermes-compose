package dev.shinglem.hermes.feature.chat

import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.model.ModelCatalog
import kotlin.time.Instant

/** 会话身份：profile 参与 key，切回同一 profile 的同一会话才算"同一份缓存"。 */
data class SessionKey(val profile: String?, val sessionId: String)

/** 待确认的模型切换（`config.set` 返回 `confirm_required` 时填充）。 */
data class ModelConfirm(
    val provider: String,
    val model: String,
    val message: String,
)

/**
 * 流式期间的一段内容，**按到达顺序**排列。
 *
 * 不能把思考 / 正文 / 工具各自堆成一个字段：一轮里它们会交替出现
 * （思考 → 工具 → 正文 → 思考 → 工具 → 正文），堆起来就丢了顺序，
 * 表现成"所有思考挤在最上面、所有正文挤在中间、工具全在最后"。
 */
sealed interface StreamSegment {
    data class Reasoning(val text: String) : StreamSegment

    /** 正文段；[sealed] 表示 `message.interim` 已把它封段，后续增量要另起一段。 */
    data class Answer(val text: String, val sealed: Boolean = false) : StreamSegment

    data class Tool(val item: ChatItem.Tool) : StreamSegment
}

/** 本轮正在生成的内容：有序段落 + 起始时间。 */
data class StreamingState(
    val segments: List<StreamSegment> = emptyList(),
    val startedAt: Instant? = null,
) {
    val isEmpty: Boolean get() = segments.isEmpty()
}

/**
 * 一条待决策的审批（server→client 请求 `approval`，或 `approval.pending` 补拉的）。
 *
 * [fromRequest] 为真表示有等待中的响应帧（应答走响应帧）；为假是重连后补拉的，
 * 应答只能走 `approval.respond`。
 */
data class PendingApproval(
    val requestId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
    val runtimeSessionId: String? = null,
    val fromRequest: Boolean = true,
    /** 发起它的会话（系统通知点击要跳到这里；跨会话时也能定位）。 */
    val sessionKey: SessionKey? = null,
)

/**
 * `clarify` 的一个问题（单问时列表只有一项）。
 *
 * `qid` 是应答 map 的键：批量请求由后端给（`q0`…），单问固定 `q0`。
 */
data class ClarifyQuestion(
    val qid: String,
    val question: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

/**
 * 待作答的 `clarify`（server→client 请求）。
 *
 * [frameId] 是 JSON-RPC 帧 id（应答必须带它）；[requestId] 是业务 id（`clarify.lock` 用）。
 * 批量请求里 `questions` 有多项，应答要用 `{answers}`；单问用 `{answer}`。
 */
data class PendingClarify(
    val requestId: String,
    val frameId: String,
    val questions: List<ClarifyQuestion>,
    val runtimeSessionId: String? = null,
    /** 发起它的会话（系统通知点击要跳到这里）。 */
    val sessionKey: SessionKey? = null,
) {
    val isBatch: Boolean get() = questions.size > 1
}

/** 上下文 / token 用量（`session.usage` 的领域投影）。 */data class SessionUsage(
    val contextUsed: Long = 0,
    val contextMax: Long = 0,
    val contextPercent: Double = 0.0,
    val totalTokens: Long = 0,
    val calls: Int = 0,
    val estimated: Boolean = false,
) {
    val ratio: Float
        get() = when {
            contextMax > 0 -> (contextUsed.toDouble() / contextMax).toFloat().coerceIn(0f, 1f)
            contextPercent > 0 -> (contextPercent / 100.0).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }

    val hasData: Boolean get() = contextMax > 0 || contextUsed > 0 || totalTokens > 0

    /** 过半即提示；四分之三以上算"该压缩了"。 */
    val level: UsageLevel
        get() = when {
            ratio >= 0.75f -> UsageLevel.HIGH
            ratio >= 0.5f -> UsageLevel.MEDIUM
            else -> UsageLevel.LOW
        }
}

enum class UsageLevel { LOW, MEDIUM, HIGH }

/** token 数的紧凑显示：`1234` → `1.2k`、`1234567` → `1.2M`。 */
fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> "${(value / 100_000) / 10.0}M"
    value >= 1_000 -> "${(value / 100) / 10.0}k"
    else -> value.toString()
}

/** 重发 / 回退：截断到这条 user 消息之前，再把它的原文重新提交。 */
data class RewindDraft(
    /** 后端消息 id（`ChatItem` 的 id 去掉前缀后即是）；必须是历史 id，不能是 `local:`。 */
    val messageId: String,
    val text: String,
    /** 截断后历史会变空（后端要 `confirm_empty_truncate`）。 */
    val emptyAfterTruncate: Boolean,
)

/** 从某条消息分支：`count` 是"保留前 N 条"（含这条）。 */
data class BranchDraft(val messageId: String, val count: Int, val title: String)

data class ChatState(
    val key: SessionKey? = null,
    val load: Loadable<List<ChatItem>> = Loadable.Idle,
    /** 只取回最近一页，更早的消息没有加载。 */
    val truncated: Boolean = false,
    /** 已读到的行数（用于向上加载的 `offset`）。 */
    val loadedRows: Int = 0,
    /** 正在向上加载更早的消息。 */
    val loadingOlder: Boolean = false,
    /** 上一次向上加载新增了多少条（供界面做锚点补偿）。 */
    val olderInserted: Int = 0,
    /** 向上加载完成的序号：界面用它触发一次滚动补偿（条数本身可能重复）。 */
    val olderGeneration: Int = 0,
    val olderError: AppError? = null,
    val streaming: StreamingState? = null,
    val sending: Boolean = false,
    val stopping: Boolean = false,
    /** 正在把这条消息作为"纠偏"注入当前轮（`session.steer`）。 */
    val steering: Boolean = false,
    val pendingApprovals: List<PendingApproval> = emptyList(),
    /** 本会话待作答的 `clarify`（应答后由 ViewModel 移除）。 */
    val pendingClarify: List<PendingClarify> = emptyList(),
    /** 本会话的 agent 后台进程（`process.list` 轮询 + `agent.terminal.output` 增量）。 */
    val backgroundTasks: List<BackgroundTask> = emptyList(),
    /** 正在终止的进程 id（按钮转圈，避免重复点）。 */
    val killingTaskId: String? = null,
    val sendError: AppError? = null,
    val lastUsageTotal: Int? = null,
    /** 上下文占用（每轮结束刷一次；点开明细才拉 breakdown）。 */
    val usage: SessionUsage? = null,
    val usageDetail: String? = null,
    val usageError: AppError? = null,
    /** 重发/回退的确认框；null = 关闭。 */
    val rewind: RewindDraft? = null,
    /** 分支确认框；null = 关闭。 */
    val branch: BranchDraft? = null,
    /** 分支/复制正在进行。 */
    val branching: Boolean = false,
    /** 当前会话的模型与推理强度，来自 `session.info`。 */
    val model: String? = null,
    val provider: String? = null,
    val reasoningEffort: String? = null,
    val modelCatalog: ModelCatalog? = null,
    val modelCatalogLoading: Boolean = false,
    val modelSwitching: Boolean = false,
    /** 贵模型确认：先弹框，用户同意后带 `confirm_expensive_model=true` 重发。 */
    val modelConfirm: ModelConfirm? = null,
    val modelError: AppError? = null,
) {
    val items: List<ChatItem> get() = load.valueOrNull.orEmpty()

    val error: AppError? get() = (load as? Loadable.Failed)?.error

    val isLoading: Boolean get() = load is Loadable.Loading

    /** 有内容正在生成。 */
    val isBusy: Boolean get() = streaming != null

    val hasApproval: Boolean get() = pendingApprovals.isNotEmpty()

    val hasClarify: Boolean get() = pendingClarify.isNotEmpty()

    /**
     * 只属于**当前会话**的审批。
     *
     * `pendingApprovals` 是跨会话的全局集合（后端在等，卡片丢了就白等到超时），
     * 但界面上不能全画出来 —— 否则在 B 会话里看到 A 的审批，用户会当成自己要处理的事情。
     * 归属不明（`sessionKey == null`，认不出 runtime 时）仍然显示，否则那张卡片永远没人看到。
     */
    val visibleApprovals: List<PendingApproval>
        get() = pendingApprovals.filter { it.sessionKey == null || it.sessionKey == key }

    /** 同 [visibleApprovals]，`clarify` 版本。 */
    val visibleClarify: List<PendingClarify>
        get() = pendingClarify.filter { it.sessionKey == null || it.sessionKey == key }

    /** 还有更早的消息可以加载。 */
    val canLoadOlder: Boolean get() = truncated && !loadingOlder && key != null
}
