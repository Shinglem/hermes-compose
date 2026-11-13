package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.feature.settings.ConfigRepository
import kotlinx.serialization.json.JsonObject

/**
 * 生产适配：把既有的 `ConfigRepository` 接到 [SecurityConfigGateway]。
 *
 * 这一层是 S2 期间的**过渡件**：S3 会把 Repository 整体下沉到 `data/` 并抽出
 * `domain/repository` 接口，届时这个类直接删掉，让 `ConfigRepository` 自己实现接口。
 * 现在保留它，是为了 S2 能只改自己这一小块、不把 13 个 Repository 一起动。
 */
class ConfigSecurityGateway(
    private val repository: ConfigRepository,
) : SecurityConfigGateway {

    override suspend fun read(profile: String?): JsonObject = repository.read(profile)

    override suspend fun patch(profile: String?, patch: JsonObject) = repository.patch(profile, patch)
}
