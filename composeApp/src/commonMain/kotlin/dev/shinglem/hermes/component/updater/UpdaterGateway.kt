package dev.shinglem.hermes.component.updater

import dev.shinglem.hermes.core.contract.BackendUpdateCheckDto

/**
 * 检查后端更新的窄接口（S5 模板：每个域一个）。
 *
 * 只暴露"这一页真正需要的那一个调用"，好处有三：
 * 1. Reducer/Executor 能在纯 JVM 里测（实现一个 30 行的假实现就行，不用起 HTTP）；
 * 2. 换传输层（REST → RPC）时只改实现，不动 Component；
 * 3. 一眼能看出这一页**不该**调用别的接口。
 */
interface UpdaterGateway {
    /**
     * `GET /api/hermes/update/check`。
     *
     * @param force true 时带 `?force=true`，绕过后端缓存。
     */
    suspend fun checkBackendUpdate(force: Boolean): BackendUpdateCheckDto
}