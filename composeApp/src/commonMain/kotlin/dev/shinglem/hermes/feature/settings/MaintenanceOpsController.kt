package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.error.toUserNotice
import dev.shinglem.hermes.platform.appDataDir
import dev.shinglem.hermes.platform.writeBytesToFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 维护页「诊断与维护」域的状态持有者（从 `MaintenanceViewModel` 里拆出）。
 *
 * 一个动作是同步 RPC + 轮询 `awaitAction`，状态机只有「空闲 / 执行中」两态，
 * 所以逻辑照搬，不引入新抽象。
 */
internal class MaintenanceOpsController(
    private val repository: MaintenanceRepository,
    private val scope: CoroutineScope,
    private val profile: () -> String?,
) {

    private val _state = MutableStateFlow(OpsUiState())
    val state: StateFlow<OpsUiState> = _state.asStateFlow()

    /** 切档案时清空本域状态（外层 `applyScope` 调用）。 */
    fun reset() {
        _state.value = OpsUiState()
    }
    // ---- 诊断与维护 ----------------------------------------------------

    fun refreshOps() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val checkpoints = repository.checkpoints(profile())
                val curator = runCatching { repository.curator(profile()) }.getOrNull()
                _state.update { it.copy(loading = false, checkpoints = checkpoints, curator = curator) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(loading = false, error = throwable.toUserNotice("/api/ops/checkpoints")) }
            }
        }
    }

    fun runAction(action: MaintenanceAction) {
        if (_state.value.running != null) return
        _state.update {
            it.copy(running = action.label, log = emptyList(), exitCode = null, notice = null, error = null)
        }
        scope.launch {
            try {
                val started = when (action) {
                    MaintenanceAction.DOCTOR -> repository.runDoctor(profile())
                    MaintenanceAction.SECURITY_AUDIT -> repository.runSecurityAudit(profile())
                    MaintenanceAction.BACKUP -> repository.runBackup(profile())
                    MaintenanceAction.CURATOR_RUN -> repository.runCurator(profile())
                    MaintenanceAction.PRUNE_CHECKPOINTS -> repository.pruneCheckpoints(profile())
                    MaintenanceAction.DUMP -> repository.runDump(profile())
                }
                val status = repository.awaitAction(started.name)
                _state.update {
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
                    val checkpoints = runCatching { repository.checkpoints(profile()) }.getOrNull()
                    _state.update { it.copy(checkpoints = checkpoints ?: it.checkpoints) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(running = null, error = throwable.toUserNotice(action.label)) }
            }
        }
    }

    fun askPruneCheckpoints() {
        _state.update { it.copy(confirmPrune = true) }
    }

    fun dismissPruneCheckpoints() {
        _state.update { it.copy(confirmPrune = false) }
    }

    fun confirmPruneCheckpoints() {
        _state.update { it.copy(confirmPrune = false) }
        runAction(MaintenanceAction.PRUNE_CHECKPOINTS)
    }

    fun toggleCuratorPaused(paused: Boolean) {
        scope.launch {
            try {
                val next = repository.setCuratorPaused(paused, profile())
                _state.update { it.copy(curator = it.curator?.copy(paused = next)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(error = throwable.toUserNotice("/api/curator/paused")) }
            }
        }
    }

    fun dismissOpsNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    /** 把服务端生成的备份 zip 下载到本机（`%LOCALAPPDATA%\hermes-compose\backups`）。 */
    fun downloadBackup() {
        val archive = _state.value.backupArchive ?: return
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, notice = null, error = null) }
        scope.launch {
            try {
                val bytes = repository.downloadBackup(archive, profile())
                val name = archive.substringAfterLast('/').substringAfterLast('\\')
                    .ifBlank { "hermes-backup.zip" }
                val target = appDataDir.trimEnd('\\', '/') + "/backups/" + name
                if (writeBytesToFile(target, bytes)) {
                    _state.update {
                        it.copy(downloading = false, downloadedPath = target, notice = "已下载到 $target")
                    }
                } else {
                    _state.update { it.copy(downloading = false, error = "写入失败：$target") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(downloading = false, error = throwable.toUserNotice("/api/ops/backup/download")) }
            }
        }
    }

    fun editImportArchive(path: String) {
        _state.update { it.copy(importArchive = path) }
    }

    /** 从备份恢复（服务端路径；`force` 由后端非交互跑 import）。 */
    fun importBackup() {
        val archive = _state.value.importArchive.trim()
        if (archive.isBlank() || _state.value.importing) return
        _state.update { it.copy(importing = true, notice = null, error = null) }
        scope.launch {
            try {
                val started = repository.importBackup(archive, force = true, profile = profile())
                val status = repository.awaitAction(started.name)
                _state.update {
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
                _state.update { it.copy(importing = false, error = throwable.toUserNotice("/api/ops/import")) }
            }
        }
    }

    /** 调试分享：同步返回链接（会脱敏日志，可关）。 */
    fun runDebugShare() {
        if (_state.value.sharing) return
        _state.update { it.copy(sharing = true, debugUrls = emptyList(), notice = null, error = null) }
        scope.launch {
            try {
                val result = repository.debugShare(lines = 200, redact = true, profile = profile())
                val urls = collectHttpUrls(result.toString())
                _state.update {
                    it.copy(
                        sharing = false,
                        debugUrls = urls,
                        notice = if (urls.isEmpty()) "未返回链接（后端可能未配置分享服务）" else "已生成分享链接",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(sharing = false, error = throwable.toUserNotice("/api/ops/debug-share")) }
            }
        }
    }

    fun dismissDebugUrls() {
        _state.update { it.copy(debugUrls = emptyList(), notice = null) }
    }
}
