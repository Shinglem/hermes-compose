package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.BackendUpdateCheckDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 「关于」页的检查更新状态。
 *
 * ⚠️ 这里查的是**后端**（`hermes` 运行时）的更新，不是本客户端自身的更新：
 * `/api/hermes/update/check` 是唯一现成的接口，客户端自更新（绿色版 zip）另做，
 * 所以按钮文案是"检查后端更新"。
 */
data class UpdateState(
    val checking: Boolean = false,
    val result: BackendUpdateCheckDto? = null,
    val error: AppError? = null,
) {
    val updateAvailable: Boolean get() = result?.updateAvailable == true
}

class UpdaterViewModel(private val rest: RestClient) : ViewModel() {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check(force: Boolean = true) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val path = if (force) "/api/hermes/update/check?force=true" else "/api/hermes/update/check"
                val element = rest.get(path, Timeouts.STARTUP_MS)
                val dto = RpcJson.json.decodeFromJsonElement(BackendUpdateCheckDto.serializer(), element)
                _state.update { it.copy(checking = false, result = dto, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        checking = false,
                        error = ErrorMapper.from(throwable, "hermes.update.check"),
                    )
                }
            }
        }
    }
}
