package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 运维类 REST 的数据形状：记忆、凭据池、用量、会话统计、后台动作。
 *
 * 这些接口只被设置中心的「维护」页组使用，不进 RPC 契约；字段名一律照后端
 * `hermes_cli/web_routers/{ops,analytics,sessions}.py` 的返回字面量，不做归一化。
 */

/** `GET /api/memory`：当前记忆 provider、可选 provider 列表、内置记忆文件大小。 */
@Serializable
data class MemoryStatusDto(
    /** 当前生效的 provider 名；空串表示没配（用内置文件）。 */
    val active: String = "",
    val providers: List<MemoryProviderStatusDto> = emptyList(),
    /** `memory` → `MEMORY.md` 字节数，`user` → `USER.md` 字节数。 */
    @SerialName("builtin_files") val builtinFiles: Map<String, Long> = emptyMap(),
)

/** 一个记忆 provider 的可用性（来自 `plugins.memory.discover_memory_providers`）。 */
@Serializable
data class MemoryProviderStatusDto(
    val name: String = "",
    val description: String = "",
    /** 插件能被成功加载（依赖齐全、接口完整）。 */
    val available: Boolean = false,
    /** 配置里写了这个 provider，但插件已经找不到。 */
    val missing: Boolean = false,
    /** 该 provider 自己的配置是否填好（env / config 块）。 */
    val configured: Boolean = false,
    /** 后端判定的状态：`ready` / `needs_config` / `unavailable` / `missing`。 */
    val status: String = "",
    /** 缺什么（依赖 / 环境变量），用于告诉用户"为什么不能启用"。 */
    val setup: MemoryProviderSetupDto = MemoryProviderSetupDto(),
) {
    /** 只有 `ready` 才允许切换 —— 后端 `_require_memory_provider_ready` 也只认这个。 */
    val ready: Boolean get() = status == "ready"
}

/** provider 的依赖与配置清单（`plugin.yaml` 的声明 + 实际探测结果）。 */
@Serializable
data class MemoryProviderSetupDto(
    @SerialName("pip_dependencies") val pipDependencies: List<String> = emptyList(),
    @SerialName("python_dependencies_declared") val pythonDependenciesDeclared: Boolean = false,
    @SerialName("external_dependencies")
    val externalDependencies: List<MemoryExternalDependencyDto> = emptyList(),
    /** 需要哪些环境变量（如 `RETAINDB_API_KEY`）。 */
    @SerialName("required_env") val requiredEnv: List<String> = emptyList(),
    /** 依赖是否已装；`false` 时 `status` 一定是 `unavailable`。 */
    @SerialName("dependencies_installed") val dependenciesInstalled: Boolean = true,
)

/** 外部命令依赖（如 ByteRover 的 `brv`）。 */
@Serializable
data class MemoryExternalDependencyDto(
    val name: String = "",
    /** 官方给的安装命令，原样展示。 */
    val install: String = "",
    /** 检查命令（`brv --version`）。 */
    val check: String = "",
)

/** `POST /api/memory/reset`。 */
@Serializable
data class MemoryResetResultDto(
    val ok: Boolean = false,
    /** 实际删除的文件名（`MEMORY.md` / `USER.md`）。 */
    val deleted: List<String> = emptyList(),
)

/**
 * `POST /api/ops/doctor` 等长任务动作的回执。
 *
 * 后端异步跑 `hermes doctor`，客户端拿 [name] 轮询 `/api/actions/{name}/status` 取日志尾巴。
 */
@Serializable
data class ActionStartedDto(
    val ok: Boolean = false,
    val pid: Int = 0,
    val name: String = "",
)

/** `POST /api/ops/backup`：除 [ActionStartedDto] 外还带回生成的归档路径。 */
@Serializable
data class BackupStartedDto(
    val ok: Boolean = false,
    val pid: Int = 0,
    val name: String = "",
    /** 服务端生成的 zip 绝对路径；`output` 由客户端指定时后端不回这一项。 */
    val archive: String? = null,
)

/** `GET /api/actions/{name}/status`。 */
@Serializable
data class ActionStatusDto(
    val name: String = "",
    val running: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
    val pid: Int? = null,
    /** 日志尾部（最多请求的 `lines` 行）。 */
    val lines: List<String> = emptyList(),
)

/** `GET /api/ops/checkpoints`：`/rollback` 影子快照的占用。 */
@Serializable
data class OpsCheckpointsDto(
    val sessions: List<CheckpointUsageDto> = emptyList(),
    @SerialName("total_bytes") val totalBytes: Long = 0,
)

/** 一个会话的检查点占用。 */
@Serializable
data class CheckpointUsageDto(
    val session: String = "",
    val files: Int = 0,
    val bytes: Long = 0,
)

/** `GET /api/curator`：技能整理器状态。 */
@Serializable
data class CuratorStatusDto(
    val enabled: Boolean = true,
    val paused: Boolean = false,
    @SerialName("interval_hours") val intervalHours: Double? = null,
    /** 可能是秒级时间戳或字符串，原样展示。 */
    @SerialName("last_run_at") val lastRunAt: String? = null,
)

/** `GET /api/credentials/pool`：按 provider 分组的凭据池。 */
@Serializable
data class CredentialPoolDto(
    val providers: List<CredentialPoolProviderDto> = emptyList(),
)

/** 一个 provider 的池化凭据。 */
@Serializable
data class CredentialPoolProviderDto(
    val provider: String = "",
    val entries: List<CredentialPoolEntryDto> = emptyList(),
)

/** 一条池化凭据（后端只给脱敏预览，明文不会回到客户端）。 */
@Serializable
data class CredentialPoolEntryDto(
    /** 1 起算，删除时按它定位。 */
    val index: Int = 0,
    val label: String = "",
    @SerialName("auth_type") val authType: String = "",
    val source: String = "",
    val priority: Int = 0,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("request_count") val requestCount: Int = 0,
    @SerialName("token_preview") val tokenPreview: String = "",
    @SerialName("has_refresh") val hasRefresh: Boolean = false,
)

/** `POST /api/credentials/pool` 的回执。 */
@Serializable
data class CredentialPoolAddResultDto(
    val ok: Boolean = false,
    val provider: String = "",
    val count: Int = 0,
)

/** `GET /api/analytics/usage`：按天、按模型、汇总三块。 */
@Serializable
data class UsageAnalyticsDto(
    @SerialName("period_days") val periodDays: Int = 0,
    val totals: UsageTotalsDto = UsageTotalsDto(),
    val daily: List<UsageDailyDto> = emptyList(),
    @SerialName("by_model") val byModel: List<UsageByModelDto> = emptyList(),
    /** 辅助任务拆分（压缩 / 标题 / 视觉…）；后端字段名 `by_task`。 */
    @SerialName("by_task") val byTask: List<UsageTaskDto> = emptyList(),
)

/** 时间窗内的汇总。 */
@Serializable
data class UsageTotalsDto(
    @SerialName("total_input") val totalInput: Long = 0,
    @SerialName("total_output") val totalOutput: Long = 0,
    @SerialName("total_cache_read") val totalCacheRead: Long = 0,
    @SerialName("total_reasoning") val totalReasoning: Long = 0,
    @SerialName("total_estimated_cost") val totalEstimatedCost: Double = 0.0,
    @SerialName("total_actual_cost") val totalActualCost: Double = 0.0,
    @SerialName("total_sessions") val totalSessions: Long = 0,
    @SerialName("total_api_calls") val totalApiCalls: Long = 0,
)

/** 一天的用量。 */
@Serializable
data class UsageDailyDto(
    val day: String = "",
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("actual_cost") val actualCost: Double = 0.0,
    val sessions: Long = 0,
    @SerialName("api_calls") val apiCalls: Long = 0,
)

/** 一个模型的用量。 */
@Serializable
data class UsageByModelDto(
    val model: String = "",
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    val sessions: Long = 0,
    @SerialName("api_calls") val apiCalls: Long = 0,
)

/** `GET /api/sessions/stats`。 */
@Serializable
data class SessionStatsDto(
    val total: Long = 0,
    @SerialName("active_store") val activeStore: Long = 0,
    val archived: Long = 0,
    val messages: Long = 0,
    @SerialName("by_source") val bySource: Map<String, Long> = emptyMap(),
)

/** `GET /api/sessions/empty/count`。 */
@Serializable
data class SessionEmptyCountDto(
    val count: Long = 0,
)

// ==== 运维扩展：记忆文件内容、钩子、配对、模型用量 ====

/** `GET /api/fs/read-text`：单文件文本内容（记忆文件查看/编辑用）。 */
@Serializable
data class FsTextDto(
    val binary: Boolean = false,
    @SerialName("byteSize") val byteSize: Long = 0,
    val language: String = "",
    @SerialName("mimeType") val mimeType: String = "",
    val path: String = "",
    val text: String = "",
    val truncated: Boolean = false,
)

/** `GET /api/ops/hooks`：已配置的 shell 钩子 + 合法事件名。 */
@Serializable
data class HooksDto(
    val hooks: List<HookDto> = emptyList(),
    @SerialName("valid_events") val validEvents: List<String> = emptyList(),
)

/** 一个 shell 钩子（`event` + `command` 是它的身份）。 */
@Serializable
data class HookDto(
    val event: String = "",
    val matcher: String? = null,
    val command: String = "",
    val timeout: Int? = null,
    /** 是否已在同意白名单里（没批准就不会触发）。 */
    val allowed: Boolean = false,
    @SerialName("approved_at") val approvedAt: Double? = null,
    /** 脚本当前是否可执行。 */
    val executable: Boolean = false,
)

/** `GET /api/pairing`：待批准请求 + 已批准用户。 */
@Serializable
data class PairingDto(
    val pending: List<PairingPendingDto> = emptyList(),
    val approved: List<PairingApprovedDto> = emptyList(),
)

@Serializable
data class PairingPendingDto(
    val platform: String = "",
    @SerialName("request_id") val requestId: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("created_at") val createdAt: Double? = null,
)

@Serializable
data class PairingApprovedDto(
    val platform: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("approved_at") val approvedAt: Double? = null,
)

/** `GET /api/analytics/models`：按模型的能力与花费卡片。 */
@Serializable
data class ModelAnalyticsDto(
    val models: List<ModelUsageCardDto> = emptyList(),
    @SerialName("period_days") val periodDays: Int = 0,
)

/** 一个模型的用量卡片（`_MODEL_CARD_KEYS`）。 */
@Serializable
data class ModelUsageCardDto(
    val model: String = "",
    val provider: String = "",
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("actual_cost") val actualCost: Double = 0.0,
    val sessions: Long = 0,
    @SerialName("api_calls") val apiCalls: Long = 0,
    @SerialName("tool_calls") val toolCalls: Long = 0,
    @SerialName("avg_tokens_per_session") val avgTokensPerSession: Double = 0.0,
)

/** 辅助任务（压缩 / 标题 / 视觉…）的成本拆分。 */
@Serializable
data class UsageTaskDto(
    val task: String = "",
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("api_calls") val apiCalls: Long = 0,
    val models: List<String> = emptyList(),
)
