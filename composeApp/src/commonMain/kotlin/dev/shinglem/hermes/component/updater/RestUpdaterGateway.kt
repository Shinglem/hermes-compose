package dev.shinglem.hermes.component.updater

import dev.shinglem.hermes.core.contract.BackendUpdateCheckDto
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * [UpdaterGateway] 的 REST 实现。
 *
 * 只做"拼路径 + 解码"，不含任何状态 —— 状态全在 Store 里。
 * 解码失败会抛异常，由 Executor 的 `catch` 统一转成 `AppError`（S3 定下的口径：
 * 不要 `getOrDefault` 吞异常，否则界面上只剩"没有更新"看不出是解析失败）。
 */
class RestUpdaterGateway(private val rest: RestClient) : UpdaterGateway {

    override suspend fun checkBackendUpdate(force: Boolean): BackendUpdateCheckDto {
        val path = if (force) "$ENDPOINT?force=true" else ENDPOINT
        val element = rest.get(path, Timeouts.STARTUP_MS)
        return RpcJson.json.decodeFromJsonElement(BackendUpdateCheckDto.serializer(), element)
    }

    private companion object {
        const val ENDPOINT = "/api/hermes/update/check"
    }
}