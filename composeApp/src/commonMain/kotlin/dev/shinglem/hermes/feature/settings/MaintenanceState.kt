package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.CredentialPoolDto
import dev.shinglem.hermes.core.contract.CuratorStatusDto
import dev.shinglem.hermes.core.contract.MemoryStatusDto
import dev.shinglem.hermes.core.contract.ModelAnalyticsDto
import dev.shinglem.hermes.core.contract.OpsCheckpointsDto
import dev.shinglem.hermes.core.contract.HooksDto
import dev.shinglem.hermes.core.contract.PairingDto
import dev.shinglem.hermes.core.contract.SessionStatsDto
import dev.shinglem.hermes.core.contract.UsageAnalyticsDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.platform.readLocalTextFile
import dev.shinglem.hermes.platform.writeBytesToFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 设置中心「维护」页组的状态：记忆、诊断与维护、凭据池、用量、会话维护。
 *
 * 五块互不依赖，各自一份 `StateFlow`；切 profile 时统一作废（[setProfile]），
 * 与 [ProviderKeysViewModel] 的语义一致 —— 上一轮的数据属于上一个 profile，不能沿用。
 */
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

data class UsageUiState(
    val loading: Boolean = false,
    val days: Int = 30,
    val data: UsageAnalyticsDto? = null,
    /** `/api/analytics/models`：按模型的卡片（独立于 usage 的 by_model）。 */
    val models: ModelAnalyticsDto? = null,
    val error: String? = null,
)

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

class MaintenanceViewModel(
    private val repository: MaintenanceRepository,
    private val configRepository: ConfigRepository,
    /** 后端数据根（`HERMES_HOME`）绝对路径：拼记忆文件路径用。 */
    private val hermesHome: String = "",
) : ViewModel() {

    private val _memory = MutableStateFlow(MemoryUiState())
    val memory: StateFlow<MemoryUiState> = _memory.asStateFlow()

    private val _ops = MutableStateFlow(OpsUiState())
    val ops: StateFlow<OpsUiState> = _ops.asStateFlow()

    private val _credentials = MutableStateFlow(CredentialPoolUiState())
    val credentials: StateFlow<CredentialPoolUiState> = _credentials.asStateFlow()

    private val _usage = MutableStateFlow(UsageUiState())
    val usage: StateFlow<UsageUiState> = _usage.asStateFlow()

    private val _sessions = MutableStateFlow(SessionMaintenanceUiState())
    val sessions: StateFlow<SessionMaintenanceUiState> = _sessions.asStateFlow()

    private val _integrations = MutableStateFlow(IntegrationUiState())
    val integrations: StateFlow<IntegrationUiState> = _integrations.asStateFlow()

    private var profile: String? = null

    /** 当前生效的配置作用域；页面用它做 `LaunchedEffect` 的 key，切档案后自动重拉。 */
    private val _profileKey = MutableStateFlow<String?>(null)
    val profileKey: StateFlow<String?> = _profileKey.asStateFlow()

    /** 全局档案变化时跟随（rail 是全局入口）：本页作用域回到该档案。 */
    fun setProfile(next: String?) {
        if (next == profile) return
        applyScope(next)
    }

    /**
     * 页面内切换「配置目标档案」：只重拉本组页面的数据。
     *
     * **不修改** shell 的 `activeProfile` —— 会话页当前使用的档案不受影响（与官方「应用到」一致）。
     */
    fun setScopeProfile(key: String) {
        val normalized = key.takeIf { it.isNotBlank() && it != DEFAULT_PROFILE_KEY }
        if (normalized == profile) return
        applyScope(normalized)
    }

    private fun applyScope(next: String?) {
        profile = next
        _profileKey.value = next
        _memory.value = MemoryUiState()
        _ops.value = OpsUiState()
        _credentials.value = CredentialPoolUiState()
        _usage.value = UsageUiState(days = _usage.value.days)
        _sessions.value = SessionMaintenanceUiState()
        _integrations.value = IntegrationUiState()
    }

    // ---- 记忆 ----------------------------------------------------------

    fun refreshMemory() {
        if (_memory.value.loading) return
        _memory.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val status = repository.memoryStatus(profile)
                val config = configRepository.read(profile)
                _memory.update { it.copy(loading = false, status = status, config = config) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _memory.update { it.copy(loading = false, error = throwable.notice("/api/memory")) }
            }
        }
    }

    /** 改一个内置层配置项（`memory.*`），只记在草稿里，保存时才发稀疏 patch。 */
    fun editMemoryConfig(path: String, text: String) {
        _memory.update { it.copy(configEdits = it.configEdits + (path to text), notice = null, error = null) }
    }

    /** 保存内置层配置：只发改动项，然后回读（后端会补默认值）。 */
    fun saveMemoryConfig() {
        val state = _memory.value
        if (state.configSaving || state.configEdits.isEmpty()) return
        val patch = buildConfigPatch(state.configEdits, MEMORY_FIELDS)
        if (patch.isEmpty()) {
            _memory.update { it.copy(error = "没有可保存的改动（数值需要是数字）") }
            return
        }
        _memory.update { it.copy(configSaving = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                configRepository.patch(profile, patch)
                val config = configRepository.read(profile)
                _memory.update {
                    it.copy(
                        configSaving = false,
                        config = config,
                        configEdits = emptyMap(),
                        notice = "已保存，对后续对话生效",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _memory.update { it.copy(configSaving = false, error = throwable.notice("/api/config")) }
            }
        }
    }

    fun askResetMemory(target: String) {
        _memory.update { it.copy(pendingReset = target, notice = null, error = null) }
    }

    fun dismissResetMemory() {
        _memory.update { it.copy(pendingReset = null) }
    }

    fun confirmResetMemory() {
        val target = _memory.value.pendingReset ?: return
        _memory.update { it.copy(pendingReset = null, busy = "reset:$target", error = null) }
        viewModelScope.launch {
            try {
                val result = repository.resetMemory(target, profile)
                val status = repository.memoryStatus(profile)
                _memory.update {
                    it.copy(
                        busy = null,
                        status = status,
                        notice = if (result.deleted.isEmpty()) {
                            "没有可删除的记忆文件"
                        } else {
                            "已删除 ${result.deleted.joinToString("、")}"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _memory.update {
                    it.copy(busy = null, error = throwable.notice("/api/memory/reset"))
                }
            }
        }
    }

    fun dismissMemoryNotice() {
        _memory.update { it.copy(notice = null, error = null) }
    }

    /** 丢弃未保存的内置层改动。 */
    fun discardMemoryConfig() {
        _memory.update { it.copy(configEdits = emptyMap(), error = null) }
    }

    // ---- 记忆文件内容（`/api/fs/read-text` / `write-text`）----------------

    /** 打开某个内置记忆文件到编辑器（[which] = `memory` / `user`）。 */
    fun openMemoryFile(which: String) {
        val path = memoryFilePath(which)
        _memory.update {
            it.copy(editingFile = which, filePath = path, fileText = "", fileLoading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val file = repository.readTextFile(path, profile)
                _memory.update {
                    it.copy(
                        fileLoading = false,
                        fileText = file.text,
                        filePath = file.path.ifBlank { path },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _memory.update { it.copy(fileLoading = false, error = throwable.notice("/api/fs/read-text")) }
            }
        }
    }

    fun editMemoryFileText(text: String) {
        _memory.update { it.copy(fileText = text) }
    }

    /** 保存编辑器内容，然后重读体积（文件大小会变）。 */
    fun saveMemoryFile() {
        val state = _memory.value
        val path = state.filePath
        if (path.isBlank() || state.fileSaving) return
        _memory.update { it.copy(fileSaving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.writeTextFile(path, state.fileText, profile)
                val status = repository.memoryStatus(profile)
                _memory.update {
                    it.copy(
                        fileSaving = false,
                        status = status,
                        editingFile = null,
                        filePath = "",
                        fileText = "",
                        notice = "已写入 ${memoryFileName(state.editingFile)}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _memory.update { it.copy(fileSaving = false, error = throwable.notice("/api/fs/write-text")) }
            }
        }
    }

    fun closeMemoryFile() {
        _memory.update { it.copy(editingFile = null, filePath = "", fileText = "") }
    }

    private fun memoryFileName(which: String?): String = if (which == "user") "USER.md" else "MEMORY.md"

    private fun memoryFilePath(which: String): String {
        val home = hermesHome.trimEnd('\\', '/')
        val dir = if (profile.isNullOrBlank()) "$home/memories" else "$home/profiles/$profile/memories"
        return "$dir/${memoryFileName(which)}"
    }

    // ---- 诊断与维护 ----------------------------------------------------

    fun refreshOps() {
        _ops.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val checkpoints = repository.checkpoints(profile)
                val curator = runCatching { repository.curator(profile) }.getOrNull()
                _ops.update { it.copy(loading = false, checkpoints = checkpoints, curator = curator) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _ops.update { it.copy(loading = false, error = throwable.notice("/api/ops/checkpoints")) }
            }
        }
    }

    fun runAction(action: MaintenanceAction) {
        if (_ops.value.running != null) return
        _ops.update {
            it.copy(running = action.label, log = emptyList(), exitCode = null, notice = null, error = null)
        }
        viewModelScope.launch {
            try {
                val started = when (action) {
                    MaintenanceAction.DOCTOR -> repository.runDoctor(profile)
                    MaintenanceAction.SECURITY_AUDIT -> repository.runSecurityAudit(profile)
                    MaintenanceAction.BACKUP -> repository.runBackup(profile)
                    MaintenanceAction.CURATOR_RUN -> repository.runCurator(profile)
                    MaintenanceAction.PRUNE_CHECKPOINTS -> repository.pruneCheckpoints(profile)
                    MaintenanceAction.DUMP -> repository.runDump(profile)
                }
                val status = repository.awaitAction(started.name)
                _ops.update {
                    it.copy(
                        running = null,
                        log = status.lines,
                        exitCode = status.exitCode,
                        backupArchive = started.archive ?: it.backupArchive,
                        notice = if (status.running) {
                            "动作仍在后台运行，稍后刷新看日志"
                        } else {
                            when (status.exitCode) {
                                null, 0 -> "已完成"
                                else -> "已结束（退出码 ${status.exitCode}），详见日志"
                            }
                        },
                    )
                }
                if (action == MaintenanceAction.PRUNE_CHECKPOINTS) {
                    val checkpoints = runCatching { repository.checkpoints(profile) }.getOrNull()
                    _ops.update { it.copy(checkpoints = checkpoints ?: it.checkpoints) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _ops.update { it.copy(running = null, error = throwable.notice(action.label)) }
            }
        }
    }

    fun askPruneCheckpoints() {
        _ops.update { it.copy(confirmPrune = true) }
    }

    fun dismissPruneCheckpoints() {
        _ops.update { it.copy(confirmPrune = false) }
    }

    fun confirmPruneCheckpoints() {
        _ops.update { it.copy(confirmPrune = false) }
        runAction(MaintenanceAction.PRUNE_CHECKPOINTS)
    }

    fun toggleCuratorPaused(paused: Boolean) {
        viewModelScope.launch {
            try {
                val next = repository.setCuratorPaused(paused, profile)
                _ops.update { it.copy(curator = it.curator?.copy(paused = next)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _ops.update { it.copy(error = throwable.notice("/api/curator/paused")) }
            }
        }
    }

    fun dismissOpsNotice() {
        _ops.update { it.copy(notice = null, error = null) }
    }

    /** 把服务端生成的备份 zip 下载到本机（`%LOCALAPPDATA%\hermes-compose\backups`）。 */
    fun downloadBackup() {
        val archive = _ops.value.backupArchive ?: return
        if (_ops.value.downloading) return
        _ops.update { it.copy(downloading = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val bytes = repository.downloadBackup(archive, profile)
                val name = archive.substringAfterLast('/').substringAfterLast('\\')
                    .ifBlank { "hermes-backup.zip" }
                val target = appDataDir.trimEnd('\\', '/') + "/backups/" + name
                if (writeBytesToFile(target, bytes)) {
                    _ops.update {
                        it.copy(downloading = false, downloadedPath = target, notice = "已下载到 $target")
                    }
                } else {
                    _ops.update { it.copy(downloading = false, error = "写入失败：$target") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _ops.update { it.copy(downloading = false, error = throwable.notice("/api/ops/backup/download")) }
            }
        }
    }

    fun editImportArchive(path: String) {
        _ops.update { it.copy(importArchive = path) }
    }

    /** 从备份恢复（服务端路径；`force` 由后端非交互跑 import）。 */
    fun importBackup() {
        val archive = _ops.value.importArchive.trim()
        if (archive.isBlank() || _ops.value.importing) return
        _ops.update { it.copy(importing = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val started = repository.importBackup(archive, force = true, profile = profile)
                val status = repository.awaitAction(started.name)
                _ops.update {
                    it.copy(
                        importing = false,
                        log = status.lines,
                        exitCode = status.exitCode,
                        notice = if (status.running) {
                            "恢复仍在后台运行，稍后看日志"
                        } else {
                            "恢复已结束（退出码 ${status.exitCode ?: 0}）"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _ops.update { it.copy(importing = false, error = throwable.notice("/api/ops/import")) }
            }
        }
    }

    /** 调试分享：同步返回链接（会脱敏日志，可关）。 */
    fun runDebugShare() {
        if (_ops.value.sharing) return
        _ops.update { it.copy(sharing = true, debugUrls = emptyList(), notice = null, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.debugShare(lines = 200, redact = true, profile = profile)
                val urls = collectHttpUrls(result.toString())
                _ops.update {
                    it.copy(
                        sharing = false,
                        debugUrls = urls,
                        notice = if (urls.isEmpty()) "未返回链接（后端可能未配置分享服务）" else "已生成分享链接",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _ops.update { it.copy(sharing = false, error = throwable.notice("/api/ops/debug-share")) }
            }
        }
    }

    fun dismissDebugUrls() {
        _ops.update { it.copy(debugUrls = emptyList(), notice = null) }
    }

    // ---- 凭据池 --------------------------------------------------------

    fun refreshCredentials() {
        _credentials.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val data = repository.credentialPool(profile)
                _credentials.update { it.copy(loading = false, data = data) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _credentials.update {
                    it.copy(loading = false, error = throwable.notice("/api/credentials/pool"))
                }
            }
        }
    }

    fun editCredentialProvider(value: String) {
        _credentials.update { it.copy(provider = value) }
    }

    fun editCredentialKey(value: String) {
        _credentials.update { it.copy(apiKey = value) }
    }

    fun editCredentialLabel(value: String) {
        _credentials.update { it.copy(label = value) }
    }

    fun addCredential() {
        val state = _credentials.value
        if (state.saving || state.provider.isBlank() || state.apiKey.isBlank()) return
        _credentials.update { it.copy(saving = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.addCredential(
                    provider = state.provider.trim(),
                    apiKey = state.apiKey.trim(),
                    label = state.label.trim().ifBlank { null },
                    profile = profile,
                )
                val data = repository.credentialPool(profile)
                _credentials.update {
                    it.copy(
                        saving = false,
                        data = data,
                        apiKey = "",
                        label = "",
                        notice = "已加入 ${result.provider}（共 ${result.count} 条）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _credentials.update {
                    it.copy(saving = false, error = throwable.notice("/api/credentials/pool"))
                }
            }
        }
    }

    fun askDeleteCredential(provider: String, index: Int) {
        _credentials.update { it.copy(pendingDelete = provider to index) }
    }

    fun dismissDeleteCredential() {
        _credentials.update { it.copy(pendingDelete = null) }
    }

    fun confirmDeleteCredential() {
        val (provider, index) = _credentials.value.pendingDelete ?: return
        _credentials.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            try {
                repository.deleteCredential(provider, index, profile)
                val data = repository.credentialPool(profile)
                _credentials.update { it.copy(data = data, notice = "已删除 $provider #$index") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _credentials.update { it.copy(error = throwable.notice("/api/credentials/pool")) }
            }
        }
    }

    fun dismissCredentialNotice() {
        _credentials.update { it.copy(notice = null, error = null) }
    }

    // ---- 用量 ----------------------------------------------------------

    fun refreshUsage(days: Int = _usage.value.days) {
        if (_usage.value.loading) return
        _usage.update { it.copy(loading = true, days = days, error = null) }
        viewModelScope.launch {
            try {
                val data = repository.usage(days, profile)
                val models = runCatching { repository.modelAnalytics(days, profile) }.getOrNull()
                _usage.update { it.copy(loading = false, data = data, models = models) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _usage.update { it.copy(loading = false, error = throwable.notice("/api/analytics/usage")) }
            }
        }
    }

    // ---- 会话维护 ------------------------------------------------------

    fun refreshSessionMaintenance() {
        _sessions.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val stats = repository.sessionStats(profile)
                val empty = runCatching { repository.emptySessionCount(profile) }.getOrNull()
                _sessions.update {
                    it.copy(loading = false, stats = stats, emptyCount = empty)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(loading = false, error = throwable.notice("/api/sessions/stats")) }
            }
        }
    }

    fun askDeleteEmptySessions() {
        _sessions.update { it.copy(confirmDeleteEmpty = true) }
    }

    fun dismissDeleteEmptySessions() {
        _sessions.update { it.copy(confirmDeleteEmpty = false) }
    }

    fun confirmDeleteEmptySessions() {
        if (_sessions.value.busy) return
        _sessions.update { it.copy(confirmDeleteEmpty = false, busy = true, error = null) }
        viewModelScope.launch {
            try {
                val deleted = repository.deleteEmptySessions(profile)
                val stats = repository.sessionStats(profile)
                val empty = runCatching { repository.emptySessionCount(profile) }.getOrNull()
                _sessions.update {
                    it.copy(
                        busy = false,
                        stats = stats,
                        emptyCount = empty,
                        notice = if (deleted > 0) "已删除 $deleted 个空会话" else "没有可删除的空会话",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(busy = false, error = throwable.notice("/api/sessions/empty")) }
            }
        }
    }

    fun dismissSessionNotice() {
        _sessions.update { it.copy(notice = null, error = null) }
    }

    // ---- 按条件清理 / 导入 ----------------------------------------------

    fun editPruneDays(value: String) {
        _sessions.update { it.copy(pruneDays = value.filter { ch -> ch.isDigit() }) }
    }

    /** 先 dry-run：后端只回统计，确认框拿它给用户看"会删多少"。 */
    fun previewPrune() {
        val days = _sessions.value.pruneDays.toIntOrNull() ?: return
        if (_sessions.value.pruning) return
        _sessions.update { it.copy(pruning = true, pruneReport = null, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val report = repository.pruneSessions(days, dryRun = true, profile = profile)
                _sessions.update { it.copy(pruning = false, pruneReport = report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(pruning = false, error = throwable.notice("/api/sessions/prune")) }
            }
        }
    }

    fun confirmPrune() {
        val days = _sessions.value.pruneDays.toIntOrNull() ?: return
        if (_sessions.value.pruning) return
        _sessions.update { it.copy(pruning = true, error = null) }
        viewModelScope.launch {
            try {
                val report = repository.pruneSessions(days, dryRun = false, profile = profile)
                val stats = repository.sessionStats(profile)
                val empty = runCatching { repository.emptySessionCount(profile) }.getOrNull()
                _sessions.update {
                    it.copy(
                        pruning = false,
                        pruneReport = null,
                        stats = stats,
                        emptyCount = empty,
                        notice = "已清理：$report",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(pruning = false, error = throwable.notice("/api/sessions/prune")) }
            }
        }
    }

    fun dismissPruneReport() {
        _sessions.update { it.copy(pruneReport = null) }
    }

    // ---- 外部集成：shell 钩子 + 配对 ------------------------------------

    fun refreshIntegrations() {
        if (_integrations.value.loading) return
        _integrations.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val hooks = repository.hooks(profile)
                val pairing = runCatching { repository.pairing(profile) }.getOrNull()
                _integrations.update { it.copy(loading = false, hooks = hooks, pairing = pairing) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(loading = false, error = throwable.notice("/api/ops/hooks")) }
            }
        }
    }

    fun editHookEvent(value: String) {
        _integrations.update { it.copy(newEvent = value) }
    }

    fun editHookCommand(value: String) {
        _integrations.update { it.copy(newCommand = value) }
    }

    fun editHookMatcher(value: String) {
        _integrations.update { it.copy(newMatcher = value) }
    }

    /** 新增钩子；后端同时写同意白名单（`approve=true`），否则它不会触发。 */
    fun addHook() {
        val state = _integrations.value
        if (state.busy != null || state.newEvent.isBlank() || state.newCommand.isBlank()) return
        _integrations.update { it.copy(busy = "add", notice = null, error = null) }
        viewModelScope.launch {
            try {
                repository.addHook(
                    event = state.newEvent.trim(),
                    command = state.newCommand.trim(),
                    matcher = state.newMatcher.trim().ifBlank { null },
                    timeout = null,
                    profile = profile,
                )
                val hooks = repository.hooks(profile)
                _integrations.update {
                    it.copy(
                        busy = null,
                        hooks = hooks,
                        newEvent = "",
                        newCommand = "",
                        newMatcher = "",
                        notice = "已添加并批准该钩子",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.notice("/api/ops/hooks")) }
            }
        }
    }

    fun deleteHook(event: String, command: String) {
        if (_integrations.value.busy != null) return
        _integrations.update { it.copy(busy = "del:$event", notice = null, error = null) }
        viewModelScope.launch {
            try {
                repository.deleteHook(event, command, profile)
                val hooks = repository.hooks(profile)
                _integrations.update { it.copy(busy = null, hooks = hooks, notice = "已删除 $event") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.notice("/api/ops/hooks")) }
            }
        }
    }

    fun approvePairing(platform: String, requestId: String) {
        if (_integrations.value.busy != null) return
        _integrations.update { it.copy(busy = "approve:$requestId", notice = null, error = null) }
        viewModelScope.launch {
            try {
                repository.approvePairing(platform, requestId, profile)
                val pairing = repository.pairing(profile)
                _integrations.update { it.copy(busy = null, pairing = pairing, notice = "已批准") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.notice("/api/pairing/approve")) }
            }
        }
    }

    fun revokePairing(platform: String, userId: String) {
        if (_integrations.value.busy != null) return
        _integrations.update { it.copy(busy = "revoke:$userId", notice = null, error = null) }
        viewModelScope.launch {
            try {
                repository.revokePairing(platform, userId, profile)
                val pairing = repository.pairing(profile)
                _integrations.update { it.copy(busy = null, pairing = pairing, notice = "已撤销") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.notice("/api/pairing/revoke")) }
            }
        }
    }

    fun dismissIntegrationNotice() {
        _integrations.update { it.copy(notice = null, error = null) }
    }

    fun importSessionsFromFile(path: String) {
        if (_sessions.value.importing) return
        val raw = readLocalTextFile(path)
        if (raw == null) {
            _sessions.update { it.copy(error = "读不到文件：$path") }
            return
        }
        _sessions.update { it.copy(importing = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.importSessions(raw, profile)
                val stats = repository.sessionStats(profile)
                _sessions.update {
                    it.copy(importing = false, stats = stats, notice = "导入完成：$result")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(importing = false, error = throwable.notice("/api/sessions/import")) }
            }
        }
    }
}

/** 统一把异常换成可显示的中文提示；[AppError] 直接用它自己的文案。 */
private fun Throwable.notice(operation: String): String =
    (this as? AppError ?: ErrorMapper.from(this, operation)).toNotice()

/** 从任意 JSON 文本里挑出 http(s) 链接（debug-share 的返回形状不固定）。 */
internal fun collectHttpUrls(text: String): List<String> =
    Regex("""https?://[^\s"'\\<>\]]+""").findAll(text)
        .map { it.value.trimEnd(',', '.', ')', '}') }
        .distinct()
        .toList()
