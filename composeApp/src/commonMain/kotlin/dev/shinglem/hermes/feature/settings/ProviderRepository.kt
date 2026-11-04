package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.EnvVarDto
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 提供方凭据的数据源：**只有** `/api/env` 这一对接口。
 *
 * 键的元数据（分组、描述、是否已设置）全部由后端给 —— 桌面端不维护 provider 表，
 * 否则 CLI 加了新 provider 这里就漏。写操作直接把明文提交给后端，由它写进 `HERMES_HOME/.env`；
 * 客户端不落盘、不缓存明文。
 */
class ProviderRepository(
    private val rest: RestClient,
) {
    suspend fun envVars(profile: String?): Map<String, EnvVarDto> {
        val element = rest.get(withProfileQuery("/api/env", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(EnvVarSerializer, element)
    }

    /**
     * 保存一个环境变量。
     *
     * `providerSetup=true` 让后端把"新填了一个 provider key"记为一次 provider setup
     * （影响共享指标里的 onboarding 状态）；对普通键没有副作用。
     */
    suspend fun setEnvVar(
        key: String,
        value: String,
        profile: String?,
        providerSetup: Boolean = true,
    ) {
        rest.send(
            RestMethod.PUT,
            withProfileQuery("/api/env", profile),
            buildJsonObject {
                put("key", key)
                put("value", value)
                profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
                put("provider_setup", providerSetup)
            },
            Timeouts.CATALOG_MS,
        )
    }

    private companion object {
        val EnvVarSerializer = MapSerializer(String.serializer(), EnvVarDto.serializer())
    }
}

/** 一个 provider 卡片：同 provider 的多个键放在一起。 */
data class ProviderGroup(
    val id: String,
    val label: String,
    val entries: List<Pair<String, EnvVarDto>>,
)

/**
 * 把 `/api/env` 的字典整理成「providers 分组 + 自定义键」。
 *
 * 只保留两类：
 * - `provider` 非空（模型提供方的凭据，含同一 provider 的多个别名键）；
 * - `custom`（用户自己在 `.env` 里加的键）。
 *
 * **隐藏** `channel_managed`（消息平台页管理同一批键）与纯工具类变量 —— 后者不在这里配。
 * 组内顺序：`provider_primary` 在前，其余按键名字典序，保证主输入框稳定在第一行。
 */
internal fun groupProviderEnvVars(env: Map<String, EnvVarDto>): List<ProviderGroup> {
    val visible = env.filterValues { it.provider.isNotBlank() && !it.channelManaged }
    val groups = visible.entries
        .groupBy { it.value.provider }
        .map { (provider, entries) ->
            val sorted = entries.sortedWith(
                compareByDescending<Map.Entry<String, EnvVarDto>> { it.value.providerPrimary }
                    .thenBy { it.key },
            )
            ProviderGroup(
                id = provider,
                label = sorted.first().value.providerLabel.ifBlank { provider },
                entries = sorted.map { it.key to it.value },
            )
        }
        .sortedBy { it.label.lowercase() }

    val custom = env.filter { (_, value) -> value.custom && !value.channelManaged }
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }
    return if (custom.isEmpty()) {
        groups
    } else {
        groups + ProviderGroup(id = CUSTOM_GROUP_ID, label = "自定义键", entries = custom)
    }
}

internal const val CUSTOM_GROUP_ID = "__custom__"

/** 该键是否已经有值（后端只给 redacted 预览）。 */
internal fun EnvVarDto.hasValue(): Boolean = isSet || !redactedValue.isNullOrBlank()
