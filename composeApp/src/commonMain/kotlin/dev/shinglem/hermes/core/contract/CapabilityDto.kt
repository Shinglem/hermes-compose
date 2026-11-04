package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 能力三页（技能 / 工具集 / 消息平台 / 定时任务）的 REST 契约子集。
 *
 * 全部走 `/api/...`，**不在 RPC 表里**（`08-data-contract.md` §3）。
 * 字段名与 wire 名一致，不重命名；缺失字段一律给默认值，避免后端加字段就解析失败。
 */
@Serializable
data class SkillDto(
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val enabled: Boolean = true,
    val usage: Int = 0,
    // bundled = 随 Hermes 发布；agent = 用户/agent 自建
    val provenance: String = "",
) {
    val provenanceLabel: String
        get() = when (provenance) {
            "bundled" -> "内置"
            "agent" -> "自建"
            else -> provenance
        }
}

@Serializable
data class ToolsetDto(
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val platform: String = "",
    @SerialName("platform_label") val platformLabel: String = "",
    val enabled: Boolean = false,
    val available: Boolean = false,
    val configured: Boolean = false,
    val tools: List<String> = emptyList(),
) {
    val displayName: String get() = label.ifBlank { name }
}

/** `GET /api/skills/content?name=` 的响应。 */
@Serializable
data class SkillContentDto(
    val name: String = "",
    val content: String = "",
)

/**
 * `GET /api/skills/hub/official` 的目录条目：官方**可选技能库**（本地扫描，离线可用）。
 *
 * `identifier` 是安装时提交的定位串（`official/<category>/<name>`）；`installed` 由后端按
 * 当前 profile 判定，前端不要自己推断。
 */
@Serializable
data class SkillCatalogEntryDto(
    val name: String = "",
    val description: String = "",
    val source: String = "",
    val identifier: String = "",
    @SerialName("trust_level") val trustLevel: String = "",
    val repo: String = "",
    val tags: List<String> = emptyList(),
    val category: String = "",
    val installed: Boolean = false,
)

@Serializable
data class SkillCatalogDto(
    val skills: List<SkillCatalogEntryDto> = emptyList(),
)

@Serializable
data class PlatformEnvVarDto(
    val key: String = "",
    val required: Boolean = false,
    @SerialName("is_set") val isSet: Boolean = false,
    @SerialName("redacted_value") val redactedValue: String? = null,
    val description: String = "",
    val prompt: String = "",
    val help: String = "",
    val url: String? = null,
    @SerialName("is_password") val isPassword: Boolean = false,
    val advanced: Boolean = false,
)

@Serializable
data class MessagingPlatformDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("docs_url") val docsUrl: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    // connected / disabled / error / …
    val state: String = "",
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("home_channel") val homeChannel: String? = null,
    @SerialName("env_vars") val envVars: List<PlatformEnvVarDto> = emptyList(),
    @SerialName("ingress_url") val ingressUrl: String? = null,
)

/** `GET /api/messaging/platforms` 外层包装（不是裸数组）。 */
@Serializable
data class MessagingPlatformsDto(
    @SerialName("env_path") val envPath: String = "",
    @SerialName("gateway_start_command") val gatewayStartCommand: String = "",
    val platforms: List<MessagingPlatformDto> = emptyList(),
)

/** `PUT /api/messaging/platforms/{id}` 的请求体。 */
@Serializable
data class MessagingPlatformUpdate(
    val enabled: Boolean? = null,
    val env: Map<String, String> = emptyMap(),
    @SerialName("clear_env") val clearEnv: List<String> = emptyList(),
    val profile: String? = null,
)

@Serializable
data class CronJobDto(
    val id: String = "",
    val name: String = "",
    val prompt: String = "",
    // 原始 cron 表达式（后端存的是 schedule 对象，列表接口会给 schedule_display）
    @SerialName("schedule_display") val scheduleDisplay: String = "",
    val deliver: String = "",
    // enabled 是后端权威开关；state 由 enabled 派生（paused / completed / error / …）
    val enabled: Boolean = true,
    val state: String = "",
    val skills: List<String> = emptyList(),
    val model: String? = null,
    val script: String? = null,
    /** 任务所属 profile（列表默认跨 profile 聚合，UI 要标出来源）。 */
    val profile: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("latest_execution") val latestExecution: CronExecutionDto? = null,
    // 跨 profile 聚合时标记来源
    @SerialName("is_default_profile") val isDefaultProfile: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { prompt.take(40).ifBlank { id } }

    val isPaused: Boolean get() = !enabled || state == "paused"
}

/** `latest_execution`（cron/executions.py 的 executions 表一行）。 */
@Serializable
data class CronExecutionDto(
    val id: String = "",
    val status: String = "",
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val error: String? = null,
)

/** `GET /api/cron/jobs` 的响应：裸数组或 `{jobs:[...]}` 都能吃。 */
@Serializable
data class CronJobsDto(val jobs: List<CronJobDto> = emptyList())

/** `POST /api/cron/jobs` 的请求体（`CronJobCreate`）。 */
@Serializable
data class CronJobCreate(
    val schedule: String,
    val prompt: String = "",
    val name: String = "",
    val deliver: String = "local",
    val paused: Boolean = false,
    val skills: List<String>? = null,
    val model: String? = null,
    val profile: String? = null,
)

/** `PUT /api/cron/jobs/{id}` 的请求体：后端收 `{updates: {...}}`。 */
@Serializable
data class CronJobUpdate(val updates: CronJobPatch)

@Serializable
data class CronJobPatch(
    val name: String? = null,
    val prompt: String? = null,
    val schedule: String? = null,
    val deliver: String? = null,
    val model: String? = null,
)

/** `GET /api/cron/jobs/{id}/runs` 的一条运行记录。 */
@Serializable
data class CronRunDto(
    val id: String = "",
    val model: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("cost_status") val costStatus: String? = null,
    @SerialName("handoff_error") val handoffError: String? = null,
    @SerialName("compression_failure_error") val compressionFailureError: String? = null,
) {
    /**
     * 这个端点返回的是 session 行，**没有** `status` 字段，只能按结束时间推导：
     * 有 `ended_at` = 跑完了；有错误字段 = 失败；否则视为运行中。
     */
    val outcome: CronRunOutcome
        get() = when {
            !handoffError.isNullOrBlank() || !compressionFailureError.isNullOrBlank() -> CronRunOutcome.FAILED
            endedAt == null -> CronRunOutcome.RUNNING
            else -> CronRunOutcome.COMPLETED
        }

    /** 时长（秒）；未结束时为 null。 */
    val durationSeconds: Long?
        get() {
            val start = startedAt ?: return null
            val end = endedAt ?: return null
            return (end - start).toLong().coerceAtLeast(0)
        }
}

enum class CronRunOutcome { RUNNING, COMPLETED, FAILED }

/** 运行历史：裸数组或 `{runs:[...]}`。 */
@Serializable
data class CronRunsDto(val runs: List<CronRunDto> = emptyList())

@Serializable
data class DeliveryTargetDto(
    val id: String = "",
    val name: String = "",
    @SerialName("home_target_set") val homeTargetSet: Boolean = false,
    @SerialName("home_env_var") val homeEnvVar: String? = null,
)

@Serializable
data class DeliveryTargetsDto(val targets: List<DeliveryTargetDto> = emptyList())

/** 蓝图表单字段（`GET /api/cron/blueprints` 的 `fields[]`）。 */
@Serializable
data class BlueprintFieldDto(
    val name: String = "",
    // time / enum / text / …
    val type: String = "",
    val label: String = "",
    val default: String = "",
    val options: List<String> = emptyList(),
    val optional: Boolean = false,
    val strict: Boolean = false,
    val help: String = "",
)

@Serializable
data class BlueprintDto(
    val key: String = "",
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val fields: List<BlueprintFieldDto> = emptyList(),
    val schedule: String = "",
    @SerialName("scheduleHuman") val scheduleHuman: String = "",
    val command: String = "",
)

@Serializable
data class BlueprintsDto(val blueprints: List<BlueprintDto> = emptyList())

/** 写操作的通用回执：`{ok, name, enabled}` / `{ok, state, message}` / `{ok, platform, hot_served}`。 */
@Serializable
data class AckDto(
    val ok: Boolean = false,
    val name: String = "",
    val enabled: Boolean? = null,
    val detail: String? = null,
    val error: String? = null,
    val message: String? = null,
    val state: String? = null,
)

/** `PUT /api/messaging/platforms/{id}` 的返回（**不是**平台快照，要回读列表）。 */
@Serializable
data class PlatformUpdateResult(
    val ok: Boolean = false,
    val platform: String = "",
    /** true = 改动已热加载；false = 该平台需要重启网关才生效。 */
    @SerialName("hot_served") val hotServed: Boolean = false,
)


