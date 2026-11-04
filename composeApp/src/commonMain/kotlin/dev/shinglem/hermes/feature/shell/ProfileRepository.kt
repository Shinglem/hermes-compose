package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.contract.ProfileDto
import dev.shinglem.hermes.core.contract.ProfilesListResultDto
import dev.shinglem.hermes.core.contract.RpcMethods
import dev.shinglem.hermes.core.error.ErrorMapper
import dev.shinglem.hermes.core.transport.GatewayClient
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** 左 rail 的一个头像项。 */
data class ProfileSummary(
    val name: String,
    val displayName: String,
    val model: String?,
    val provider: String?,
    val isDefault: Boolean,
    val skillCount: Int,
) {
    val label: String get() = displayName.ifBlank { name }

    /** 头像占位字：取首个字符，中文取字、英文取大写字母。 */
    val initial: String get() = label.take(1).uppercase()
}

class ProfileRepository(private val gateway: GatewayClient) {
    suspend fun list(): List<ProfileSummary> {
        val element = try {
            gateway.call(RpcMethods.PROFILES_LIST, JsonObject(emptyMap()), Timeouts.STARTUP_MS)
        } catch (throwable: Throwable) {
            throw ErrorMapper.from(throwable, RpcMethods.PROFILES_LIST)
        }
        val dto = RpcJson.json.decodeFromJsonElement(ProfilesListResultDto.serializer(), element)
        return dto.profiles.map(ProfileDto::toSummary)
    }
}

private fun ProfileDto.toSummary(): ProfileSummary = ProfileSummary(
    name = name,
    displayName = displayName,
    model = model,
    provider = provider,
    isDefault = isDefault,
    skillCount = skillCount,
)
