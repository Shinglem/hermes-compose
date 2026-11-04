package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.error.toNotice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.EnvVarDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 提供方密钥页的状态。 */
data class ProviderKeysState(
    val env: Loadable<Map<String, EnvVarDto>> = Loadable.Idle,
    /** 用户正在输入的新值（key → 明文）；保存成功后清空。 */
    val drafts: Map<String, String> = emptyMap(),
    val busyKey: String? = null,
    val error: String? = null,
    /** 刚保存成功的键，用于行内"已保存"提示。 */
    val savedKey: String? = null,
    /** 搜索词：匹配键名 / 描述 / provider 名（provider 有几十个，没有搜索就得滚很久）。 */
    val query: String = "",
) {
    fun groups(): List<ProviderGroup> {
        val all = groupProviderEnvVars(env.valueOrNull.orEmpty())
        val text = query.trim()
        if (text.isEmpty()) return all
        return all.mapNotNull { group ->
            val entries = group.entries.filter { (key, info) ->
                key.contains(text, ignoreCase = true) ||
                    info.description.contains(text, ignoreCase = true) ||
                    group.label.contains(text, ignoreCase = true)
            }
            if (entries.isEmpty()) null else group.copy(entries = entries)
        }
    }

    /** 已配置的数量（含 custom）。 */
    fun configuredCount(): Int = env.valueOrNull.orEmpty().values.count { it.hasValue() }
}

/**
 * 提供方密钥：读 `/api/env`、按 provider 分组、逐个键保存。
 *
 * 明文只存在 [ProviderKeysState.drafts]（内存），保存后立刻清掉 —— 客户端不缓存凭据。
 */
class ProviderKeysViewModel(
    private val repository: ProviderRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProviderKeysState())
    val state: StateFlow<ProviderKeysState> = _state.asStateFlow()

    private var profile: String? = null
    private var loaded = false

    fun setProfile(next: String?) {
        if (next == profile) return
        profile = next
        loaded = false
        _state.update { it.copy(env = Loadable.Idle, drafts = emptyMap(), error = null, savedKey = null) }
    }

    fun onVisible() {
        if (!loaded) refresh()
    }

    fun refresh() {
        val stale = _state.value.env.valueOrNull
        viewModelScope.launch {
            if (stale == null) _state.update { it.copy(env = Loadable.Loading) }
            try {
                val env = repository.envVars(profile)
                loaded = true
                _state.update { it.copy(env = Loadable.Ready(env), error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val error = throwable as? AppError ?: AppError.Unknown(throwable.message.orEmpty())
                _state.update { it.copy(env = Loadable.Failed(error, stale)) }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun edit(key: String, value: String) {        _state.update { it.copy(drafts = it.drafts + (key to value), error = null, savedKey = null) }
    }

    /**
     * 保存一个键。
     *
     * 后端写完会同时清理 `config.yaml` 里的旧镜像值（它比 `.env` 优先级高），
     * 所以成功后必须**回读**——否则列表里的 redacted 预览还是旧值。
     */
    fun save(key: String) {
        val value = _state.value.drafts[key]?.trim().orEmpty()
        if (value.isEmpty() || _state.value.busyKey != null) return
        _state.update { it.copy(busyKey = key, error = null, savedKey = null) }
        viewModelScope.launch {
            try {
                repository.setEnvVar(key, value, profile)
                val env = repository.envVars(profile)
                _state.update {
                    it.copy(
                        env = Loadable.Ready(env),
                        drafts = it.drafts - key,
                        busyKey = null,
                        savedKey = key,
                    )
                }
                Log.info("提供方密钥已保存：$key")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val message = (throwable as? AppError)?.toNotice() ?: (throwable.message ?: "未知错误")
                Log.error("保存提供方密钥失败：$key", throwable)
                _state.update { it.copy(busyKey = null, error = "$key：$message") }
            }
        }
    }

    fun dismissSaved() {
        _state.update { it.copy(savedKey = null, error = null) }
    }
}
