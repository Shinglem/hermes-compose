package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.CustomEndpointActivatedDto
import dev.shinglem.hermes.core.contract.CustomEndpointProbeDto
import dev.shinglem.hermes.core.contract.CustomEndpointSavedDto
import dev.shinglem.hermes.core.contract.CustomEndpointUpdateDto
import dev.shinglem.hermes.core.contract.CustomEndpointsDto
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 提供方 · 自定义端点（OpenAI 兼容）的数据源。
 *
 * 后端把 v12 的 `providers.<key>` 与历史 `custom_providers:` 列表合并成一份响应，
 * 桌面端只按 `id` 操作。**明文 key 只在保存的这一次请求里出现**，列表接口只回预览。
 */
class CustomEndpointRepository(
    private val rest: RestClient,
) {
    suspend fun list(profile: String?): CustomEndpointsDto {
        val element = rest.get(withProfileQuery("/api/providers/custom-endpoints", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(CustomEndpointsDto.serializer(), element)
    }

    suspend fun upsert(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointSavedDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/providers/custom-endpoints", profile),
            RpcJson.json.encodeToJsonElement(CustomEndpointUpdateDto.serializer(), update).jsonObject,
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(CustomEndpointSavedDto.serializer(), element)
    }

    /**
     * 探测端点：后端会请求 `{base}/models`，再按保存的模式（或自动探测）打一次真实传输路由。
     *
     * 只有 HTTP 200 且能解析出模型列表才算 `ok`；`reachable=false` 表示连不上，
     * `reachable=true, ok=false` 是警告（可以照存，例如端点不暴露模型列表）。
     */
    suspend fun validate(profile: String?, update: CustomEndpointUpdateDto): CustomEndpointProbeDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/providers/custom-endpoints/validate", profile),
            RpcJson.json.encodeToJsonElement(CustomEndpointUpdateDto.serializer(), update).jsonObject,
            PROBE_TIMEOUT_MS,
        )
        return RpcJson.json.decodeFromJsonElement(CustomEndpointProbeDto.serializer(), element)
    }

    suspend fun activate(profile: String?, endpointId: String): CustomEndpointActivatedDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/providers/custom-endpoints/$endpointId/activate", profile),
            null,
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(CustomEndpointActivatedDto.serializer(), element)
    }

    suspend fun delete(profile: String?, endpointId: String) {
        rest.send(
            RestMethod.DELETE,
            withProfileQuery("/api/providers/custom-endpoints/$endpointId", profile),
            null,
            Timeouts.WRITE_MS,
        )
    }

    private companion object {
        /** 探测要跑两三次往返（`/models` + 传输路由），比普通写请求留更多时间。 */
        const val PROBE_TIMEOUT_MS = 40_000L
    }
}

/** 编辑表单里的一个端点草稿。空 [id] = 新建。 */
data class EndpointDraft(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** `""` = 运行时自动探测；其余取 [API_MODES] 的值。 */
    val apiMode: String = "",
    val contextDraft: String = "",
    val discoverModels: Boolean = true,
    val makeDefault: Boolean = false,
) {
    val isNew: Boolean get() = id.isBlank()

    /** 表单必填项都齐了才允许保存/探测。 */
    val canSubmit: Boolean get() = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * 转成请求体。
     *
     * `apiKey` 为空时**必须传 null 而不是空串** —— 后端把空串当成"清空已存的键"，
     * 而编辑一个已有端点时用户往往不重填 key。
     */
    fun toUpdate(): CustomEndpointUpdateDto = CustomEndpointUpdateDto(
        id = id,
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.takeIf { it.isNotEmpty() },
        apiMode = apiMode,
        contextLength = contextDraft.trim().toIntOrNull()?.takeIf { it > 0 },
        discoverModels = discoverModels,
        makeDefault = makeDefault,
    )
}

/** 端点可选的传输模式：`""` 交给运行时按 URL 自动判定。 */
val API_MODES: List<Pair<String, String>> = listOf(
    "" to "自动探测",
    "chat_completions" to "Chat Completions",
    "codex_responses" to "Codex Responses",
    "anthropic_messages" to "Anthropic Messages",
)

internal fun apiModeLabel(mode: String): String =
    API_MODES.firstOrNull { it.first == mode }?.second ?: mode.ifBlank { "自动探测" }

/** 探测结果 → 一行给人看的提示（含是否只是警告）。 */
internal fun probeMessage(probe: CustomEndpointProbeDto): String = when {
    probe.ok && probe.message.isBlank() -> "连接正常：发现 ${probe.models.size} 个模型"
    probe.ok -> probe.message
    probe.message.isBlank() -> if (probe.reachable) "端点在，但探测未通过" else "连不上端点"
    else -> probe.message
}
