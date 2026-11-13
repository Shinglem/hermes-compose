package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.core.contract.CustomEndpointActivatedDto
import dev.shinglem.hermes.core.contract.CustomEndpointProbeDto
import dev.shinglem.hermes.core.contract.CustomEndpointSavedDto
import dev.shinglem.hermes.core.contract.CustomEndpointUpdateDto
import dev.shinglem.hermes.core.contract.CustomEndpointsDto
import dev.shinglem.hermes.feature.settings.CustomEndpointRepository

/**
 * 自定义端点页需要的五个后端动作。
 *
 * 与 [SecurityConfigGateway] / [ProviderKeysGateway] 同一意图：让 Component 能在纯 JVM
 * 里测（`CustomEndpointRepository` 依赖 `RestClient` 这个具体类）。S3 抽
 * `domain/repository` 时统一删掉。
 */
interface CustomEndpointGateway {
    suspend fun list(profile: String?): CustomEndpointsDto

    suspend fun upsert(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointSavedDto

    /** 只探测、不保存 —— 端点可能故意不暴露模型列表，所以探测结果不拦保存。 */
    suspend fun validate(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointProbeDto

    suspend fun activate(profile: String?, endpointId: String): CustomEndpointActivatedDto

    suspend fun delete(profile: String?, endpointId: String)
}

/** 生产适配。S3 后删除。 */
class ConfigCustomEndpointGateway(
    private val repository: CustomEndpointRepository,
) : CustomEndpointGateway {

    override suspend fun list(profile: String?): CustomEndpointsDto = repository.list(profile)

    override suspend fun upsert(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointSavedDto =
        repository.upsert(profile, update)

    override suspend fun validate(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointProbeDto =
        repository.validate(profile, update)

    override suspend fun activate(profile: String?, endpointId: String): CustomEndpointActivatedDto =
        repository.activate(profile, endpointId)

    override suspend fun delete(profile: String?, endpointId: String) = repository.delete(profile, endpointId)
}
