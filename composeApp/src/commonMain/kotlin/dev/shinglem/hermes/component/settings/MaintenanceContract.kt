package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.CredentialPoolDto
import dev.shinglem.hermes.core.contract.CuratorStatusDto
import dev.shinglem.hermes.core.contract.HooksDto
import dev.shinglem.hermes.core.contract.MemoryStatusDto
import dev.shinglem.hermes.core.contract.ModelAnalyticsDto
import dev.shinglem.hermes.core.contract.OpsCheckpointsDto
import dev.shinglem.hermes.core.contract.PairingDto
import dev.shinglem.hermes.core.contract.SessionStatsDto
import dev.shinglem.hermes.core.contract.UsageAnalyticsDto
import dev.shinglem.hermes.feature.settings.ConfigFieldSpec
import dev.shinglem.hermes.feature.settings.readPath
import kotlinx.serialization.json.JsonObject

/**
 * 维护页组的状态：**六块互不依赖**的领域，各自一份不可变快照。
 *
 * 为什么合成一个 `MaintenanceState` 而不是六个独立 Component：
 * 它们共用同一个 Repository、同一次 profile 切换、同一个"维护页"入口 ——
 * 拆六个 Component 会让 UI 侧多六层嵌套，而共享的东西（profile、生命周期）反而要重复传。
 * 真正的隔离靠 [MaintenanceIntent] 的分组与六个独立的 loading/busy 字段（下面每个
 * `XxxUiState` 仍是自己的小状态机）。
 */
@Immutable
data class MaintenanceState(
    val memory: MemoryUiState = MemoryUiState(),
    val ops: OpsUiState = OpsUiState(),
    val credentials: CredentialPoolUiState = CredentialPoolUiState(),
    val usage: UsageUiState = UsageUiState(),
    val sessions: SessionMaintenanceUiState = SessionMaintenanceUiState(),
    val integrations: IntegrationUiState = IntegrationUiState(),
    val profile: String? = null,
)

@Immutable
data class MemoryUiState(
    val loading: Boolean = false,
    val status: MemoryStatusDto? = null,
    /** 正在执行的动作（`reset:<target>`），用于禁用按钮。 */
    val busy: String? = null,
    /** 等二次确认的重置目标（`all` / `memory` / `user`）。 */
    val pendingReset: String? = null,
    /** `GET /api/config` 快照（只读内置层的四项配置）。 */
    val config: JsonObject? = null,
    /** 用户改过但未保存的配置项（点分路径 → 文本）。 */
    val configEdits: Map<String, String> = emptyMap(),
    val configSaving: Boolean = false,
    /** 正在编辑的记忆文件（`memory` / `user`）；null = 编辑器关闭。 */
    val editingFile: String? = null,
    val filePath: String = "",
    val fileText: String = "",
    val fileLoading: Boolean = false,
    val fileSaving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    /** 表单显示值：改过的用编辑值，否则读后端配置。 */
    fun configValue(spec: ConfigFieldSpec): String =
        configEdits[spec.path] ?: config?.readPath(spec.path).orEmpty()
}

@Immutable
data class OpsUiState(
    val loading: Boolean = false,
    /** 正在跑的动作名（中文），null 表示空闲。 */
    val running: String? = null,
    /** 动作日志尾部；只在本次会话内保留。 */
    val log: List<String> = emptyList(),
    val exitCode: Int? = null,
    val checkpoints: OpsCheckpointsDto? = null,
    val curator: CuratorStatusDto? = null,
    /** 备份产物的路径（服务端生成时才有）。 */
    val backupArchive: String? = null,
    val confirmPrune: Boolean = false,
    /** 把服务端备份 zip 下载到本机（`%LOCALAPPDATA%\hermes-compose\backups`）。 */
    val downloading: Boolean = false,
    val downloadedPath: String? = null,
    /** 恢复用的 archive 路径（服务端可读的路径）。 */
    val importArchive: String = "",
    val importing: Boolean = false,
    /** 调试分享返回的链接（可复制）。 */
    val sharing: Boolean = false,
    val debugUrls: List<String> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

@Immutable
data class CredentialPoolUiState(
    val loading: Boolean = false,
    val data: CredentialPoolDto? = null,
    val provider: String = "",
    val apiKey: String = "",
    val label: String = "",
    val saving: Boolean = false,
    /** 等确认删除的 `(provider, index)`。 */
    val pendingDelete: Pair<String, Int>? = null,
    val notice: String? = null,
    val error: String? = null,
)

@Immutable
data class UsageUiState(
    val loading: Boolean = false,
    val days: Int = 30,
    val data: UsageAnalyticsDto? = null,
    /** `/api/analytics/models`：按模型的卡片（独立于 usage 的 by_model）。 */
    val models: ModelAnalyticsDto? = null,
    val error: String? = null,
)

@Immutable
data class SessionMaintenanceUiState(
    val loading: Boolean = false,
    val stats: SessionStatsDto? = null,
    val emptyCount: Long? = null,
    val busy: Boolean = false,
    val confirmDeleteEmpty: Boolean = false,
    /** 按条件清理：天数（默认 90）与 dry-run 报告。 */
    val pruneDays: String = "90",
    val pruneReport: JsonObject? = null,
    val pruning: Boolean = false,
    /** 正在导入（选完文件后提交）。 */
    val importing: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/** 外部集成：shell 钩子 + 配对（远程管理端）白名单。 */
@Immutable
data class IntegrationUiState(
    val loading: Boolean = false,
    val hooks: HooksDto? = null,
    val pairing: PairingDto? = null,
    val newEvent: String = "",
    val newCommand: String = "",
    val newMatcher: String = "",
    /** 正在执行的动作标识（删除/批准/撤销）。 */
    val busy: String? = null,
    val notice: String? = null,
    val error: String? = null,
)

/** 维护页能触发的后台动作。 */
enum class MaintenanceAction(val label: String) {
    DOCTOR("运行诊断（doctor）"),
    SECURITY_AUDIT("安全审计"),
    BACKUP("备份配置与数据"),
    CURATOR_RUN("立即整理技能"),
    PRUNE_CHECKPOINTS("清理检查点"),
    DUMP("导出诊断转储"),
}
