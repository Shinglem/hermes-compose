package dev.shinglem.hermes.core.transport

import dev.shinglem.hermes.core.config.AppOptionsHolder

/**
 * 超时分级（`.design/草案/08-data-contract.md` §2.5）。
 *
 * 不要统一成 30s：`prompt.submit` 的完成信号是 `event` 通知，
 * 短超时会把长回答误判为失败。
 *
 * 三个可调值取自 `config/options.json`，**每次现取**（`get()`）而不是常量 ——
 * 用户改了文件立即生效，而所有调用点（`Timeouts.DEFAULT_MS`）写法不变。
 * 其余是内部时序常量，不对外开放：调错会把活着的连接误判为半开。
 */
object Timeouts {
    /** 交互式 RPC / REST 调用。 */
    val DEFAULT_MS: Long
        get() = AppOptionsHolder.current.network.requestTimeoutMs

    /** 启动期批量读取（profiles / config / models / cron）。 */
    const val STARTUP_MS = 60_000L

    /** `prompt.submit` 的 ack 超时。 */
    val PROMPT_SUBMIT_MS: Long
        get() = AppOptionsHolder.current.network.promptAckTimeoutMs

    /** 健康探测，要快速发现后端死了。 */
    const val HEALTH_MS = 8_000L

    /** 技能库目录（本地扫描 152 项）与 hub 安装提交：后端要读一批文件，比普通读慢。 */
    const val CATALOG_MS = 60_000L

    /** 写操作（保存配置 / 开关 / 触发任务）：后端要落 .env 或 config.yaml，比读慢。 */
    val WRITE_MS: Long
        get() = AppOptionsHolder.current.network.writeTimeoutMs

    const val HEARTBEAT_INTERVAL_MS = 15_000L

    /** 超过这个时间没有任何入站帧就判定半开连接。 */
    const val HEARTBEAT_DEADLINE_MS = 45_000L

    const val MAX_RECONNECT_DELAY_MS = 30_000L

    /** 指数退避：1s, 2s, 4s … 上限 [MAX_RECONNECT_DELAY_MS]。 */
    fun reconnectDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        return (1_000L shl shift).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
}
