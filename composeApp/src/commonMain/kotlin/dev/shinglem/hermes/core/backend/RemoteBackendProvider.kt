package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `remote` 模式：后端已经在别处跑着，客户端不需要拉起任何进程。
 *
 * 不做启动探活 —— 连不上由 [dev.shinglem.hermes.core.transport.GatewayClient]
 * 的重连状态机表达（它已经有退避与错误展示），这里再拦一层只会让启动页多一次假失败。
 */
class RemoteBackendProvider(
    private val target: GatewayTarget,
    private val token: String? = null,
) : BackendProvider {
    override val kind: BackendKind = BackendKind.REMOTE

    override val sessionToken: String? get() = token?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow<BackendBootState>(BackendBootState.Ready(target))
    override val state: StateFlow<BackendBootState> = _state.asStateFlow()

    override val logs: StateFlow<List<BackendLogLine>> =
        MutableStateFlow<List<BackendLogLine>>(emptyList()).asStateFlow()

    override fun currentTarget(): GatewayTarget = target

    override suspend fun start() {
        if (_state.value !is BackendBootState.Ready) {
            _state.value = BackendBootState.Ready(target)
        }
    }

    override suspend fun stop() = Unit

    override fun shutdown() = Unit
}
