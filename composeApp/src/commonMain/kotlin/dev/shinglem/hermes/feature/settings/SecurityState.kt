package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.serialization.json.JsonObject

data class SecurityState(
    val config: Loadable<JsonObject> = Loadable.Idle,
    /** 用户改过的字段（点分路径 → 文本）；保存后清空。 */
    val edits: Map<String, String> = emptyMap(),
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 表单显示值：改过的用编辑值，否则读后端配置。 */
    fun valueOf(spec: ConfigFieldSpec): String =
        edits[spec.path] ?: config.valueOrNull?.readPath(spec.path).orEmpty()

    val dirty: Boolean get() = edits.isNotEmpty()
}

/**
 * 安全三页（审批 / 隐私与网络 / 检查点）的状态。
 *
 * 三页共用一份 `GET /api/config`；保存时只把**改动过的**字段拼成稀疏 patch，
 * 后端 deep-merge。改完回读校验（后端的归一化视图会补默认值，只有回读才看到真值）。
 */
class SecurityViewModel(
    private val repository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SecurityState())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    private var profile: String? = null
    private var loaded = false

    fun setProfile(next: String?) {
        if (next == profile) return
        profile = next
        loaded = false
        _state.update { SecurityState() }
    }

    fun onVisible() {
        if (!loaded) refresh()
    }

    fun refresh() {
        val stale = _state.value.config.valueOrNull
        viewModelScope.launch {
            if (stale == null) _state.update { it.copy(config = Loadable.Loading) }
            try {
                val config = repository.read(profile)
                loaded = true
                _state.update { it.copy(config = Loadable.Ready(config), error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                _state.update { it.copy(config = Loadable.Failed(error, stale)) }
            }
        }
    }

    fun edit(path: String, text: String) {
        _state.update { it.copy(edits = it.edits + (path to text), notice = null, error = null) }
    }

    fun save(specs: List<ConfigFieldSpec>) {
        val state = _state.value
        if (state.busyKey != null || state.edits.isEmpty()) return
        val patch = buildConfigPatch(state.edits, specs)
        if (patch.isEmpty()) {
            _state.update { it.copy(error = "没有可保存的改动（数值需要是数字）") }
            return
        }
        _state.update { it.copy(busyKey = "config", error = null, notice = null) }
        viewModelScope.launch {
            try {
                repository.patch(profile, patch)
                Log.info("后端配置已保存：${patch.keys.joinToString(",")}")
                val fresh = repository.read(profile)
                _state.update {
                    it.copy(config = Loadable.Ready(fresh), edits = emptyMap(), busyKey = null, notice = "已保存")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存失败：${throwable.describe()}") }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }
}

private fun Throwable.describe(): String =
    (this as? AppError)?.toNotice() ?: (message ?: "未知错误")
