package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.AuxModelsDto
import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.core.contract.ModelInfoAdminDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelEntry
import dev.shinglem.hermes.feature.model.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 贵模型二次确认的待办：确认后用同一组参数带 `confirm_expensive_model` 重发。 */
data class PendingModelConfirm(
    val scope: String,
    val provider: String,
    val model: String,
    val task: String?,
    val message: String,
)

data class ModelSettingsState(
    val info: Loadable<ModelInfoAdminDto> = Loadable.Idle,
    val catalog: Loadable<ModelCatalog> = Loadable.Idle,
    /** 主模型页里待应用的选择（空 = 未改动）。 */
    val provider: String = "",
    val model: String = "",
    /** 上下文覆盖输入框（空串 = 自动）。 */
    val contextDraft: String = "",
    val pending: PendingModelConfirm? = null,
    val aux: Loadable<AuxModelsDto> = Loadable.Idle,
    /** 备用模型链（主模型不可用时的回退顺序）。 */
    val fallback: Loadable<List<FallbackEntry>> = Loadable.Idle,
    /** 磁盘上 `fallback_providers` 原本是不是数组形状（写回要原样保持）。 */
    val fallbackKeepArray: Boolean = false,
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 目录中当前 provider 的模型列表。 */
    fun modelsOf(provider: String): List<ModelEntry> =
        catalog.valueOrNull?.groups?.firstOrNull { it.slug == provider }?.models.orEmpty()

    val providerOptions: List<Pair<String, String>>
        get() = catalog.valueOrNull?.groups
            ?.filter { it.models.isNotEmpty() }
            ?.map { it.slug to it.name }
            .orEmpty()

    val selectionDirty: Boolean
        get() {
            val current = info.valueOrNull ?: return false
            return (provider.isNotBlank() && provider != current.provider) ||
                (model.isNotBlank() && model != current.model)
        }
}

/**
 * 模型配置页（主模型 / 辅助模型）。
 *
 * 目录走 `ModelRepository.options`（RPC，带 1h 磁盘缓存）；读写 profile 默认模型走
 * `ModelAdminRepository`（REST）。`POST /api/model/set` 的 `confirm_required` 一定要走
 * [confirmPending]，不能吞掉。
 */
class ModelSettingsViewModel(
    private val models: ModelRepository,
    private val admin: ModelAdminRepository,
    private val config: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelSettingsState())
    val state: StateFlow<ModelSettingsState> = _state.asStateFlow()

    private var profile: String? = null
    private var loaded = false

    fun setProfile(next: String?) {
        if (next == profile) return
        profile = next
        loaded = false
        _state.update { ModelSettingsState() }
    }

    fun onVisible() {
        if (!loaded) {
            refresh()
            loadCatalog()
            loadAux()
            loadFallback()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(info = if (it.info.valueOrNull == null) Loadable.Loading else it.info) }
            try {
                val info = admin.info(profile)
                loaded = true
                _state.update {
                    it.copy(
                        info = Loadable.Ready(info),
                        provider = it.provider.ifBlank { info.provider },
                        model = it.model.ifBlank { info.model },
                        contextDraft = when {
                            it.contextDraft.isNotBlank() -> it.contextDraft
                            info.configContextLength > 0 -> info.configContextLength.toString()
                            else -> ""
                        },
                        error = null,
                    )
                }
            } catch (throwable: Throwable) {
                _state.update { it.copy(info = Loadable.Failed(throwable.asAppError())) }
            }
        }
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _state.update { it.copy(catalog = if (it.catalog.valueOrNull == null) Loadable.Loading else it.catalog) }
            try {
                val catalog = models.options(profile, refresh = false)
                _state.update { it.copy(catalog = Loadable.Ready(catalog)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(catalog = Loadable.Failed(throwable.asAppError())) }
            }
        }
    }

    fun selectProvider(provider: String) {
        val first = _state.value.modelsOf(provider).firstOrNull()?.model.orEmpty()
        _state.update { it.copy(provider = provider, model = first, notice = null) }
    }

    fun selectModel(model: String) {
        _state.update { it.copy(model = model, notice = null) }
    }

    fun editContext(text: String) {
        _state.update { it.copy(contextDraft = text.filter { ch -> ch.isDigit() }, notice = null) }
    }

    /** 应用主模型（`scope="main"`，写 profile 默认模型，对新会话生效）。 */
    fun applyMain() {
        val state = _state.value
        if (state.busyKey != null) return
        val provider = state.provider.ifBlank { state.info.valueOrNull?.provider.orEmpty() }
        val model = state.model.ifBlank { state.info.valueOrNull?.model.orEmpty() }
        if (provider.isBlank() && model.isBlank()) return
        assign("main", provider, model, task = null)
    }

    /** 用户点了「仍然使用」——带 confirm 标记重发上一条。 */
    fun confirmPending() {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        assign(pending.scope, pending.provider, pending.model, pending.task, confirmExpensive = true)
    }

    fun dismissPending() {
        _state.update { it.copy(pending = null) }
    }

    /** 保存上下文覆盖：0 或空 = 回到自动。 */
    fun saveContextLength() {
        val state = _state.value
        if (state.busyKey != null) return
        val length = state.contextDraft.trim().toLongOrNull()
        _state.update { it.copy(busyKey = "context", error = null, notice = null) }
        viewModelScope.launch {
            try {
                config.patch(profile, contextLengthPatch(length))
                _state.update { it.copy(busyKey = null, notice = "上下文长度已保存") }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存失败：${throwable.describe()}") }
            }
        }
    }

    fun loadAux() {
        viewModelScope.launch {
            _state.update { it.copy(aux = if (it.aux.valueOrNull == null) Loadable.Loading else it.aux) }
            try {
                val aux = admin.auxiliary(profile)
                _state.update { it.copy(aux = Loadable.Ready(aux)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(aux = Loadable.Failed(throwable.asAppError())) }
            }
        }
    }

    /** 把当前选中的模型钉到某个辅助任务槽位。 */
    fun assignAuxTask(task: AuxTaskDto) {
        val state = _state.value
        val provider = state.provider.ifBlank { state.info.valueOrNull?.provider.orEmpty() }
        val model = state.model.ifBlank { state.info.valueOrNull?.model.orEmpty() }
        if (model.isBlank()) {
            _state.update { it.copy(error = "先在主模型页选一个模型，再钉到辅助任务") }
            return
        }
        assign("auxiliary", provider, model, task.task)
    }

    fun resetAux() {
        if (_state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "aux-reset", error = null, notice = null) }
        viewModelScope.launch {
            try {
                admin.resetAuxiliary(profile)
                _state.update { it.copy(busyKey = null, notice = "已把辅助模型全部重置为跟随主模型") }
                loadAux()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "重置失败：${throwable.describe()}") }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }

    // ── 备用模型 ────────────────────────────────────────────────────────

    fun loadFallback() {
        viewModelScope.launch {
            _state.update { it.copy(fallback = if (it.fallback.valueOrNull == null) Loadable.Loading else it.fallback) }
            try {
                val (entries, keepArray) = readFallbackProviders(config.read(profile))
                _state.update { it.copy(fallback = Loadable.Ready(entries), fallbackKeepArray = keepArray) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(fallback = Loadable.Failed(throwable.asAppError())) }
            }
        }
    }

    /** 把当前选中的模型追加到回退链尾部（重复的不加）。 */
    fun addFallback() {
        val state = _state.value
        val provider = state.provider.ifBlank { state.info.valueOrNull?.provider.orEmpty() }
        val model = state.model.ifBlank { state.info.valueOrNull?.model.orEmpty() }
        if (provider.isBlank() || model.isBlank()) {
            _state.update { it.copy(error = "先在主模型页选好 provider 与模型") }
            return
        }
        val current = state.fallback.valueOrNull.orEmpty()
        if (current.any { it.provider == provider && it.model == model }) {
            _state.update { it.copy(error = "这条已经在回退链里了") }
            return
        }
        _state.update {
            it.copy(fallback = Loadable.Ready(current + FallbackEntry(provider, model)), error = null, notice = null)
        }
    }

    fun removeFallback(index: Int) {
        val current = _state.value.fallback.valueOrNull.orEmpty()
        if (index !in current.indices) return
        _state.update {
            it.copy(
                fallback = Loadable.Ready(current.filterIndexed { i, _ -> i != index }),
                notice = null,
                error = null,
            )
        }
    }

    fun saveFallback() {
        val state = _state.value
        if (state.busyKey != null) return
        val entries = state.fallback.valueOrNull ?: return
        _state.update { it.copy(busyKey = "fallback", error = null, notice = null) }
        viewModelScope.launch {
            try {
                config.patch(profile, fallbackPatch(entries, state.fallbackKeepArray))
                Log.info("备用模型已保存：${entries.joinToString { it.label }}")
                _state.update {
                    it.copy(
                        busyKey = null,
                        notice = if (entries.isEmpty()) "已清空回退链" else "备用模型已保存（${entries.size} 条）",
                    )
                }
                loadFallback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存失败：${throwable.describe()}") }
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private fun assign(
        scope: String,
        provider: String,
        model: String,
        task: String?,
        confirmExpensive: Boolean = false,
    ) {
        if (_state.value.busyKey != null) return
        val key = task?.let { "aux:$it" } ?: "main"
        _state.update { it.copy(busyKey = key, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val result = admin.assignModel(profile, scope, provider, model, task, confirmExpensive)
                if (result.confirmRequired && !confirmExpensive) {
                    _state.update {
                        it.copy(
                            busyKey = null,
                            pending = PendingModelConfirm(
                                scope = scope,
                                provider = provider,
                                model = model,
                                task = task,
                                message = result.confirmMessage.ifBlank { "该模型价格较高，确认使用？" },
                            ),
                        )
                    }
                    return@launch
                }
                val stale = result.staleAux
                Log.info("模型指派已保存：scope=$scope task=$task provider=$provider model=$model")
                _state.update {
                    it.copy(
                        busyKey = null,
                        notice = buildString {
                            append(if (scope == "main") "主模型已切换" else "辅助任务已更新")
                            if (stale.isNotEmpty()) append("（这些辅助槽仍指向旧模型：${stale.joinToString("、")}）")
                        },
                    )
                }
                refresh()
                if (scope == "auxiliary") loadAux()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存失败：${throwable.describe()}") }
            }
        }
    }
}

private fun Throwable.asAppError(): AppError =
    this as? AppError ?: AppError.Unknown(message.orEmpty())

private fun Throwable.describe(): String =
    (this as? AppError)?.toNotice() ?: (message ?: "未知错误")
