package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.error.toUserNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 维护页「记忆」域的状态持有者（从 `MaintenanceViewModel` 里拆出，见 S8 的文件规模清理）。
 *
 * 与其余域互不共享可变状态：只依赖两个 Repository、`hermesHome`（拼记忆文件路径）与
 * 一个 `profile()` 读取器（作用域由外层统一维护，这里不持有第二份真相）。
 */
internal class MaintenanceMemoryController(
    private val repository: MaintenanceRepository,
    private val configRepository: ConfigRepository,
    private val hermesHome: String,
    private val scope: CoroutineScope,
    private val profile: () -> String?,
) {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    /** 切档案时清空本域状态（外层 `applyScope` 调用）。 */
    fun reset() {
        _state.value = MemoryUiState()
    }
    // ---- 记忆 ----------------------------------------------------------

    fun refreshMemory() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val status = repository.memoryStatus(profile())
                val config = configRepository.read(profile())
                _state.update { it.copy(loading = false, status = status, config = config) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(loading = false, error = throwable.toUserNotice("/api/memory")) }
            }
        }
    }

    /** 改一个内置层配置项（`memory.*`），只记在草稿里，保存时才发稀疏 patch。 */
    fun editMemoryConfig(path: String, text: String) {
        _state.update { it.copy(configEdits = it.configEdits + (path to text), notice = null, error = null) }
    }

    /** 保存内置层配置：只发改动项，然后回读（后端会补默认值）。 */
    fun saveMemoryConfig() {
        val state = _state.value
        if (state.configSaving || state.configEdits.isEmpty()) return
        val patch = buildConfigPatch(state.configEdits, MEMORY_FIELDS)
        if (patch.isEmpty()) {
            _state.update { it.copy(error = "没有可保存的改动（数值需要是数字）") }
            return
        }
        _state.update { it.copy(configSaving = true, notice = null, error = null) }
        scope.launch {
            try {
                configRepository.patch(profile(), patch)
                val config = configRepository.read(profile())
                _state.update {
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
                _state.update { it.copy(configSaving = false, error = throwable.toUserNotice("/api/config")) }
            }
        }
    }

    fun askResetMemory(target: String) {
        _state.update { it.copy(pendingReset = target, notice = null, error = null) }
    }

    fun dismissResetMemory() {
        _state.update { it.copy(pendingReset = null) }
    }

    fun confirmResetMemory() {
        val target = _state.value.pendingReset ?: return
        _state.update { it.copy(pendingReset = null, busy = "reset:$target", error = null) }
        scope.launch {
            try {
                val result = repository.resetMemory(target, profile())
                val status = repository.memoryStatus(profile())
                _state.update {
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
                _state.update {
                    it.copy(busy = null, error = throwable.toUserNotice("/api/memory/reset"))
                }
            }
        }
    }

    fun dismissMemoryNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    /** 丢弃未保存的内置层改动。 */
    fun discardMemoryConfig() {
        _state.update { it.copy(configEdits = emptyMap(), error = null) }
    }

    // ---- 记忆文件内容（`/api/fs/read-text` / `write-text`）----------------

    /** 打开某个内置记忆文件到编辑器（[which] = `memory` / `user`）。 */
    fun openMemoryFile(which: String) {
        val path = memoryFilePath(which)
        _state.update {
            it.copy(editingFile = which, filePath = path, fileText = "", fileLoading = true, error = null)
        }
        scope.launch {
            try {
                val file = repository.readTextFile(path, profile())
                _state.update {
                    it.copy(
                        fileLoading = false,
                        fileText = file.text,
                        filePath = file.path.ifBlank { path },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(fileLoading = false, error = throwable.toUserNotice("/api/fs/read-text")) }
            }
        }
    }

    fun editMemoryFileText(text: String) {
        _state.update { it.copy(fileText = text) }
    }

    /** 保存编辑器内容，然后重读体积（文件大小会变）。 */
    fun saveMemoryFile() {
        val state = _state.value
        val path = state.filePath
        if (path.isBlank() || state.fileSaving) return
        _state.update { it.copy(fileSaving = true, error = null) }
        scope.launch {
            try {
                repository.writeTextFile(path, state.fileText, profile())
                val status = repository.memoryStatus(profile())
                _state.update {
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
                _state.update { it.copy(fileSaving = false, error = throwable.toUserNotice("/api/fs/write-text")) }
            }
        }
    }

    fun closeMemoryFile() {
        _state.update { it.copy(editingFile = null, filePath = "", fileText = "") }
    }

    private fun memoryFileName(which: String?): String = if (which == "user") "USER.md" else "MEMORY.md"

    private fun memoryFilePath(which: String): String {
        val home = hermesHome.trimEnd('\\', '/')
        val dir = if (profile().isNullOrBlank()) "$home/memories" else "$home/profiles/${profile()}/memories"
        return "$dir/${memoryFileName(which)}"
    }
}
