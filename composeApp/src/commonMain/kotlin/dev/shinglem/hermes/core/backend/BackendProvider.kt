package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.transport.GatewayTarget
import kotlinx.coroutines.flow.StateFlow

/**
 * 后端来源抽象（`06-本地后端与双模式.md` §3.1）。上层只依赖它，
 * Repository / ViewModel / UI 不需要知道是本地子进程还是远端服务。
 */
interface BackendProvider {
    val kind: BackendKind

    val state: StateFlow<BackendBootState>

    /** 子进程输出（只保留最近若干行），启动页与「查看日志」用。 */
    val logs: StateFlow<List<BackendLogLine>>

    /** 当前应连的地址；尚未 Ready 时返回配置里的占位目标。 */
    fun currentTarget(): GatewayTarget

    /**
     * 后端认的会话令牌（`X-Hermes-Session-Token`）。
     *
     * 只读用途：把同一个令牌交给需要直连本后端的**其它客户端**（如官方桌面端的
     * `HERMES_DESKTOP_REMOTE_TOKEN`），否则它们只能落在"配置后端"向导里。
     * LOCAL 模式返回我们注入子进程的那个值，REMOTE 返回配置里解析出的值。
     */
    val sessionToken: String? get() = null

    /** 幂等；已 Ready 立即返回，失败后再次调用等于重试。 */
    suspend fun start()

    suspend fun stop()

    /**
     * 同步关停（子进程终止本身是阻塞调用）。窗口关闭路径用它：
     * `AppContainer.close()` 在 commonMain，那里不能用 `runBlocking`。
     */
    fun shutdown()
}
