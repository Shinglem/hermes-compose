package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.core.contract.ProfileAdminDto
import dev.shinglem.hermes.core.contract.ProfileCreateResultDto
import dev.shinglem.hermes.core.contract.ProfileExportResultDto
import dev.shinglem.hermes.core.contract.ProfileSoulDto
import dev.shinglem.hermes.feature.settings.ProfilesAdminRepository

/**
 * 配置档案页需要的后端动作。
 *
 * **注意它不按 profile 分**：这一页管理的是"所有档案"本身，所以每个方法都没有
 * `profile` 参数 —— 与其它 settings 域不同（那些都是"改某个 profile 的设置"）。
 */
interface ProfilesGateway {
    suspend fun list(): List<ProfileAdminDto>

    suspend fun soul(name: String): ProfileSoulDto

    suspend fun saveSoul(name: String, content: String)

    suspend fun create(name: String, cloneFrom: String?, soul: String?): ProfileCreateResultDto

    suspend fun delete(name: String)

    suspend fun rename(name: String, newName: String)

    suspend fun import(archive: String, name: String?): String

    suspend fun export(name: String, output: String): ProfileExportResultDto
}

/** 生产适配。S3 后删除。 */
class ConfigProfilesGateway(
    private val repository: ProfilesAdminRepository,
) : ProfilesGateway {

    override suspend fun list(): List<ProfileAdminDto> = repository.list()

    override suspend fun soul(name: String): ProfileSoulDto = repository.soul(name)

    override suspend fun saveSoul(name: String, content: String) = repository.saveSoul(name, content)

    override suspend fun create(name: String, cloneFrom: String?, soul: String?): ProfileCreateResultDto =
        repository.create(name = name, cloneFrom = cloneFrom, soul = soul)

    override suspend fun delete(name: String) = repository.delete(name)

    override suspend fun rename(name: String, newName: String) = repository.rename(name, newName)

    override suspend fun import(archive: String, name: String?): String = repository.import(archive, name)

    override suspend fun export(name: String, output: String): ProfileExportResultDto =
        repository.export(name, output)
}
