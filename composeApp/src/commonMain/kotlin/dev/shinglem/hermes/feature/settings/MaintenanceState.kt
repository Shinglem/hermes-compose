package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.CredentialPoolDto
import dev.shinglem.hermes.core.contract.CuratorStatusDto
import dev.shinglem.hermes.core.contract.HooksDto
import dev.shinglem.hermes.core.contract.MemoryStatusDto
import dev.shinglem.hermes.core.contract.ModelAnalyticsDto
import dev.shinglem.hermes.core.contract.OpsCheckpointsDto
import dev.shinglem.hermes.core.contract.PairingDto
import dev.shinglem.hermes.core.contract.SessionStatsDto
import dev.shinglem.hermes.core.contract.UsageAnalyticsDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.error.toUserNotice
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.platform.readLocalTextFile
import dev.shinglem.hermes.platform.writeBytesToFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 维护页六块状态的**别名**（S2 过渡）。
 *
 * 真正的定义在 `component/settings/MaintenanceContract.kt`（那里能被 Component 复用）；
 * 这里保留同名别名，让 6 个 UI 页面一行不用改。等它们都改成收 Component 后一起删。
 */
typealias MemoryUiState = dev.shinglem.hermes.component.settings.MemoryUiState
typealias OpsUiState = dev.shinglem.hermes.component.settings.OpsUiState
typealias CredentialPoolUiState = dev.shinglem.hermes.component.settings.CredentialPoolUiState
typealias UsageUiState = dev.shinglem.hermes.component.settings.UsageUiState
typealias SessionMaintenanceUiState = dev.shinglem.hermes.component.settings.SessionMaintenanceUiState
typealias IntegrationUiState = dev.shinglem.hermes.component.settings.IntegrationUiState
typealias MaintenanceAction = dev.shinglem.hermes.component.settings.MaintenanceAction

/** 维护页组的状态持有者（S2 过渡形态，见 `DefaultMaintenanceComponent` 的类注释）。 */
class MaintenanceViewModel(
    private val repository: MaintenanceRepository,
    private val configRepository: ConfigRepository,
    /** 后端数据根（`HERMES_HOME`）绝对路径：拼记忆文件路径用。 */
    private val hermesHome: String = "",
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    /** 记忆域（`MaintenanceMemoryController`，本类只做转发）。 */
    private val memoryController = MaintenanceMemoryController(
        repository = repository,
        configRepository = configRepository,
        hermesHome = hermesHome,
        scope = scope,
        profile = { profile },
    )

    /** 诊断与维护域（`MaintenanceOpsController`，本类只做转发）。 */
    private val opsController = MaintenanceOpsController(
        repository = repository,
        scope = scope,
        profile = { profile },
    )

    val memory: StateFlow<MemoryUiState> = memoryController.state
    val ops: StateFlow<OpsUiState> = opsController.state

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
        memoryController.reset()
        opsController.reset()
        _credentials.value = CredentialPoolUiState()
        _usage.value = UsageUiState(days = _usage.value.days)
        _sessions.value = SessionMaintenanceUiState()
        _integrations.value = IntegrationUiState()
    }

    // ---- 记忆（转发到 MaintenanceMemoryController）----------------------

    fun refreshMemory() = memoryController.refreshMemory()
    fun editMemoryConfig(path: String, text: String) = memoryController.editMemoryConfig(path, text)
    fun saveMemoryConfig() = memoryController.saveMemoryConfig()
    fun askResetMemory(target: String) = memoryController.askResetMemory(target)
    fun dismissResetMemory() = memoryController.dismissResetMemory()
    fun confirmResetMemory() = memoryController.confirmResetMemory()
    fun dismissMemoryNotice() = memoryController.dismissMemoryNotice()
    fun discardMemoryConfig() = memoryController.discardMemoryConfig()
    fun openMemoryFile(which: String) = memoryController.openMemoryFile(which)
    fun editMemoryFileText(text: String) = memoryController.editMemoryFileText(text)
    fun saveMemoryFile() = memoryController.saveMemoryFile()
    fun closeMemoryFile() = memoryController.closeMemoryFile()

    // ---- 诊断与维护（转发到 MaintenanceOpsController）-------------------

    fun refreshOps() = opsController.refreshOps()
    fun runAction(action: MaintenanceAction) = opsController.runAction(action)
    fun askPruneCheckpoints() = opsController.askPruneCheckpoints()
    fun dismissPruneCheckpoints() = opsController.dismissPruneCheckpoints()
    fun confirmPruneCheckpoints() = opsController.confirmPruneCheckpoints()
    fun toggleCuratorPaused(paused: Boolean) = opsController.toggleCuratorPaused(paused)
    fun dismissOpsNotice() = opsController.dismissOpsNotice()
    fun downloadBackup() = opsController.downloadBackup()
    fun editImportArchive(path: String) = opsController.editImportArchive(path)
    fun importBackup() = opsController.importBackup()
    fun runDebugShare() = opsController.runDebugShare()
    fun dismissDebugUrls() = opsController.dismissDebugUrls()

    // ---- 凭据池 --------------------------------------------------------

    fun refreshCredentials() {
        _credentials.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val data = repository.credentialPool(profile)
                _credentials.update { it.copy(loading = false, data = data) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _credentials.update {
                    it.copy(loading = false, error = throwable.toUserNotice("/api/credentials/pool"))
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
        scope.launch {
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
                    it.copy(saving = false, error = throwable.toUserNotice("/api/credentials/pool"))
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
        scope.launch {
            try {
                repository.deleteCredential(provider, index, profile)
                val data = repository.credentialPool(profile)
                _credentials.update { it.copy(data = data, notice = "已删除 $provider #$index") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _credentials.update { it.copy(error = throwable.toUserNotice("/api/credentials/pool")) }
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
        scope.launch {
            try {
                val data = repository.usage(days, profile)
                val models = runCatching { repository.modelAnalytics(days, profile) }.getOrNull()
                _usage.update { it.copy(loading = false, data = data, models = models) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _usage.update { it.copy(loading = false, error = throwable.toUserNotice("/api/analytics/usage")) }
            }
        }
    }

    // ---- 会话维护 ------------------------------------------------------

    fun refreshSessionMaintenance() {
        _sessions.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val stats = repository.sessionStats(profile)
                val empty = runCatching { repository.emptySessionCount(profile) }.getOrNull()
                _sessions.update {
                    it.copy(loading = false, stats = stats, emptyCount = empty)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(loading = false, error = throwable.toUserNotice("/api/sessions/stats")) }
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
        scope.launch {
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
                _sessions.update { it.copy(busy = false, error = throwable.toUserNotice("/api/sessions/empty")) }
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
        scope.launch {
            try {
                val report = repository.pruneSessions(days, dryRun = true, profile = profile)
                _sessions.update { it.copy(pruning = false, pruneReport = report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(pruning = false, error = throwable.toUserNotice("/api/sessions/prune")) }
            }
        }
    }

    fun confirmPrune() {
        val days = _sessions.value.pruneDays.toIntOrNull() ?: return
        if (_sessions.value.pruning) return
        _sessions.update { it.copy(pruning = true, error = null) }
        scope.launch {
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
                _sessions.update { it.copy(pruning = false, error = throwable.toUserNotice("/api/sessions/prune")) }
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
        scope.launch {
            try {
                val hooks = repository.hooks(profile)
                val pairing = runCatching { repository.pairing(profile) }.getOrNull()
                _integrations.update { it.copy(loading = false, hooks = hooks, pairing = pairing) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(loading = false, error = throwable.toUserNotice("/api/ops/hooks")) }
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
        scope.launch {
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
                _integrations.update { it.copy(busy = null, error = throwable.toUserNotice("/api/ops/hooks")) }
            }
        }
    }

    fun deleteHook(event: String, command: String) {
        if (_integrations.value.busy != null) return
        _integrations.update { it.copy(busy = "del:$event", notice = null, error = null) }
        scope.launch {
            try {
                repository.deleteHook(event, command, profile)
                val hooks = repository.hooks(profile)
                _integrations.update { it.copy(busy = null, hooks = hooks, notice = "已删除 $event") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.toUserNotice("/api/ops/hooks")) }
            }
        }
    }

    fun approvePairing(platform: String, requestId: String) {
        if (_integrations.value.busy != null) return
        _integrations.update { it.copy(busy = "approve:$requestId", notice = null, error = null) }
        scope.launch {
            try {
                repository.approvePairing(platform, requestId, profile)
                val pairing = repository.pairing(profile)
                _integrations.update { it.copy(busy = null, pairing = pairing, notice = "已批准") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.toUserNotice("/api/pairing/approve")) }
            }
        }
    }

    fun revokePairing(platform: String, userId: String) {
        if (_integrations.value.busy != null) return
        _integrations.update { it.copy(busy = "revoke:$userId", notice = null, error = null) }
        scope.launch {
            try {
                repository.revokePairing(platform, userId, profile)
                val pairing = repository.pairing(profile)
                _integrations.update { it.copy(busy = null, pairing = pairing, notice = "已撤销") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _integrations.update { it.copy(busy = null, error = throwable.toUserNotice("/api/pairing/revoke")) }
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
        scope.launch {
            try {
                val result = repository.importSessions(raw, profile)
                val stats = repository.sessionStats(profile)
                _sessions.update {
                    it.copy(importing = false, stats = stats, notice = "导入完成：$result")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _sessions.update { it.copy(importing = false, error = throwable.toUserNotice("/api/sessions/import")) }
            }
        }
    }
}

/** 统一把异常换成可显示的中文提示；[AppError] 直接用它自己的文案。 */

/** 从任意 JSON 文本里挑出 http(s) 链接（debug-share 的返回形状不固定）。 */
internal fun collectHttpUrls(text: String): List<String> =
    Regex("""https?://[^\s"'\\<>\]]+""").findAll(text)
        .map { it.value.trimEnd(',', '.', ')', '}') }
        .distinct()
        .toList()
