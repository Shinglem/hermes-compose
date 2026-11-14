package dev.shinglem.hermes.component.backend

import dev.shinglem.hermes.core.backend.BackendProvider
import dev.shinglem.hermes.core.transport.ConnectionState
import dev.shinglem.hermes.core.transport.GatewayClient

/**
 * 后端管理页的窄接口（S5 模板）。
 *
 * 与其它 6 个域不同，这一页的数据源是**两条已有的流**而不是 REST：
 * - `backend.state` / `backend.logs`：后端自己的启停进度与子进程输出；
 * - `gateway.connection`：传输层连接状态。
 *
 * 抽成接口的目的是**可测**：`BackendExecutor` 的订阅逻辑（合并三流、去重、
 * 在动作期间抑制 notice 抖动）能用两个 `MutableStateFlow` 验证，不必起真后端。
 */
interface BackendGateway {
    val boot: kotlinx.coroutines.flow.StateFlow<dev.shinglem.hermes.core.backend.BackendBootState>
    val logs: kotlinx.coroutines.flow.StateFlow<List<dev.shinglem.hermes.core.backend.BackendLogLine>>
    val connection: kotlinx.coroutines.flow.StateFlow<ConnectionState>

    /** `backend.kind`：`LOCAL` / `REMOTE`，用来在诊断文本里标注来源。 */
    val kind: dev.shinglem.hermes.core.backend.BackendKind?

    suspend fun start()
    suspend fun stop()
    suspend fun revalidate()

    /** 重连传输层：`gateway.stop()` + `gateway.start()`。 */
    fun reconnect()
}

/**
 * [BackendGateway] 的真实实现：直接转发给 `BackendProvider` 与 `GatewayClient`。
 *
 * 刻意**不加缓存、不加转换** —— 这两条流本身已经是 `StateFlow`，
 * 任何中间层都只会引入"两份状态不同步"的风险。
 */
class ProviderBackendGateway(
    private val backend: BackendProvider,
    private val gateway: GatewayClient,
) : BackendGateway {

    override val boot get() = backend.state
    override val logs get() = backend.logs
    override val connection: kotlinx.coroutines.flow.StateFlow<ConnectionState> get() = gateway.connection
    override val kind get() = backend.kind

    override suspend fun start() = backend.start()
    override suspend fun stop() = backend.stop()
    override suspend fun revalidate() = backend.revalidate()

    override fun reconnect() {
        gateway.stop()
        gateway.start()
    }
}