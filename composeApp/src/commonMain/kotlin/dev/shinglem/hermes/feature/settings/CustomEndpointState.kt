package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.CustomEndpointDto
import dev.shinglem.hermes.core.contract.CurrentModelDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 提供方 · 自定义端点页的状态。
 *
 * [draft] 非空表示正在编辑（新建或改某个端点），此时列表让位给表单；保存或取消后回到列表。
 */
data class CustomEndpointState(
    val endpoints: Loadable<List<CustomEndpointDto>> = Loadable.Idle,
    val current: CurrentModelDto = CurrentModelDto(),
    val draft: EndpointDraft? = null,
    val validating: Boolean = false,
    /** 最近一次探测的结论（人话）；[probeOk] 为空表示只是警告。 */
    val probe: String? = null,
    val probeOk: Boolean? = null,
    /** 待确认删除的端点 id。 */
    val pendingDelete: String? = null,
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 自定义端点页（OpenAI 兼容提供方）。
 *
 * 写的是后端 profile 的 `config.yaml`：新建/编辑 `/api/providers/custom-endpoints`，
 * 激活会同时把主模型槽位指过去。桌面端不缓存明文 key。
 */
class CustomEndpointViewModel(
    private val repository: CustomEndpointRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CustomEndpointState())
    val state: StateFlow<CustomEndpointState> = _state.asStateFlow()

    private var profile: String? = null
    private var loaded = false

    fun setProfile(next: String?) {
        if (next == profile) return
        profile = next
        loaded = false
        _state.update { CustomEndpointState() }
    }

    fun onVisible() {
        if (!loaded) refresh()
    }

    fun refresh() {
        loaded = true
        _state.update {
            it.copy(endpoints = if (it.endpoints.valueOrNull == null) Loadable.Loading else it.endpoints)
        }
        viewModelScope.launch {
            try {
                val response = repository.list(profile)
                _state.update {
                    it.copy(endpoints = Loadable.Ready(response.endpoints), current = response.current)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(endpoints = Loadable.Failed(throwable.asEndpointError())) }
            }
        }
    }

    fun startCreate() {
        _state.update {
            it.copy(
                draft = EndpointDraft(apiMode = ""),
                probe = null,
                probeOk = null,
                error = null,
                notice = null,
            )
        }
    }

    fun startEdit(endpoint: CustomEndpointDto) {
        _state.update {
            it.copy(
                draft = EndpointDraft(
                    id = endpoint.id,
                    name = endpoint.name,
                    baseUrl = endpoint.baseUrl,
                    model = endpoint.model,
                    apiKey = "",
                    apiMode = endpoint.apiMode,
                    contextDraft = endpoint.contextLength?.toString().orEmpty(),
                    discoverModels = endpoint.discoverModels,
                    makeDefault = false,
                ),
                probe = null,
                probeOk = null,
                error = null,
                notice = null,
            )
        }
    }

    fun editDraft(next: EndpointDraft) {
        _state.update { it.copy(draft = next, error = null) }
    }

    fun cancelEdit() {
        _state.update { it.copy(draft = null, probe = null, probeOk = null, error = null) }
    }

    /** 探测端点：只报告结论，不拦保存（端点可能故意不暴露模型列表）。 */
    fun validate() {
        val draft = _state.value.draft ?: return
        if (_state.value.validating || !draft.canSubmit) return
        _state.update { it.copy(validating = true, probe = null, probeOk = null, error = null) }
        viewModelScope.launch {
            try {
                val probe = repository.validate(profile, draft.toUpdate())
                _state.update {
                    it.copy(
                        validating = false,
                        probe = probeMessage(probe),
                        probeOk = probe.ok,
                        draft = resolveProbeUrl(it.draft, probe.resolvedBaseUrl),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(validating = false, probe = "探测失败：${throwable.describeEndpointError()}", probeOk = false)
                }
            }
        }
    }

    fun save() {
        val state = _state.value
        val draft = state.draft ?: return
        if (state.busyKey != null || !draft.canSubmit) return
        _state.update { it.copy(busyKey = "save", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val saved = repository.upsert(profile, draft.toUpdate())
                Log.info("自定义端点已保存：${draft.name} → ${saved.id}")
                _state.update {
                    it.copy(
                        busyKey = null,
                        draft = null,
                        probe = null,
                        probeOk = null,
                        endpoints = Loadable.Ready(saved.endpoints),
                        current = saved.current,
                        notice = if (draft.isNew) "已添加 ${draft.name}" else "已更新 ${draft.name}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存失败：${throwable.describeEndpointError()}") }
            }
        }
    }

    fun activate(endpointId: String) {
        if (_state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "activate:$endpointId", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val result = repository.activate(profile, endpointId)
                _state.update {
                    it.copy(
                        busyKey = null,
                        notice = "主模型已指向 ${result.provider} / ${result.model}（对新会话生效）",
                    )
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "激活失败：${throwable.describeEndpointError()}") }
            }
        }
    }

    fun askDelete(endpointId: String) {
        _state.update { it.copy(pendingDelete = endpointId) }
    }

    fun dismissDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val endpointId = _state.value.pendingDelete ?: return
        if (_state.value.busyKey != null) return
        _state.update { it.copy(busyKey = "delete:$endpointId", pendingDelete = null, error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.delete(profile, endpointId)
                Log.info("自定义端点已删除：$endpointId")
                _state.update { it.copy(busyKey = null, notice = "已删除 $endpointId") }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "删除失败：${throwable.describeEndpointError()}") }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }
}

/**
 * 探测后把 base_url 换成真正供出 `/models` 的那个地址。
 *
 * 后端保存时也是这么改的（运行时会在保存的 URL 后直接拼 `/chat/completions`），
 * 表单里先同步过来，用户看到的就是最终会落盘的地址。
 */
internal fun resolveProbeUrl(draft: EndpointDraft?, resolved: String?): EndpointDraft? {
    val target = resolved?.trim()?.trimEnd('/').orEmpty()
    return when {
        draft == null || target.isBlank() -> draft
        draft.baseUrl.trim().trimEnd('/') == target -> draft
        else -> draft.copy(baseUrl = target)
    }
}

private fun Throwable.asEndpointError(): AppError =
    this as? AppError ?: AppError.Unknown(message.orEmpty())

private fun Throwable.describeEndpointError(): String =
    (this as? AppError)?.toNotice() ?: (message ?: "未知错误")
