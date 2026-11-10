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

    /**
     * 强制重新解析后端地址（即使已经 Ready）。
     *
     * 后端可能被**外部**重启并换端口（daemon 的端口是 OS 分配的），而 [start] 在 Ready 时会
     * 直接返回，于是客户端会一直连旧端口 —— 表现就是"怎么发消息都没反应"。
     * 传输层连续连接失败时调用它，让 [currentTarget] 跟上新的 `api_origin`。
     * 默认实现就是 [start]（远端模式没有"换端口"这回事）。
     */
    suspend fun revalidate() {
        start()
    }

    suspend fun stop()

    /**
     * 同步关停（子进程终止本身是阻塞调用）。窗口关闭路径用它：
     * `AppContainer.close()` 在 commonMain，那里不能用 `runBlocking`。
     */
    fun shutdown()
}
