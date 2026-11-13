package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.core.contract.MoaConfigDto
import dev.shinglem.hermes.core.contract.MoaConfigUpdateDto
import dev.shinglem.hermes.feature.settings.ModelAdminRepository

/** MoA 页需要的两个后端动作（形状同前几个域，S3 后删除）。 */
interface MoaGateway {
    suspend fun moa(profile: String?): MoaConfigDto

    suspend fun saveMoa(profile: String?, update: MoaConfigUpdateDto): MoaConfigDto
}

/** 生产适配。S3 后删除。 */
class ConfigMoaGateway(
    private val repository: ModelAdminRepository,
) : MoaGateway {

    override suspend fun moa(profile: String?): MoaConfigDto = repository.moa(profile)

    override suspend fun saveMoa(profile: String?, update: MoaConfigUpdateDto): MoaConfigDto =
        repository.saveMoa(profile, update)
}
