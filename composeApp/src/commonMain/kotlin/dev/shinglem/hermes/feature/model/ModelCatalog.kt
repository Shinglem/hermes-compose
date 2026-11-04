package dev.shinglem.hermes.feature.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `model.options` 的返回（`hermes_cli/inventory.py::build_models_payload`，
 * 与 REST `/api/model/options` 同一 payload，类型见
 * `apps/shared/src/gateway-contract.generated.ts` 的 `ModelOptionsResult`）。
 */
@Serializable
data class ModelOptionsDto(
    val providers: List<ModelProviderDto> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class ModelProviderDto(
    val slug: String = "",
    val name: String = "",
    val models: List<String> = emptyList(),
    @SerialName("total_models") val totalModels: Int? = null,
    @SerialName("is_current") val isCurrent: Boolean? = null,
    val authenticated: Boolean? = null,
    @SerialName("featured_models") val featuredModels: List<String>? = null,
    val capabilities: Map<String, ModelCapabilityDto>? = null,
    val pricing: Map<String, ModelPricingDto>? = null,
    val warning: String? = null,
)

@Serializable
data class ModelCapabilityDto(
    val fast: Boolean = false,
    val reasoning: Boolean = false,
    @SerialName("can_disable_reasoning") val canDisableReasoning: Boolean? = null,
)

@Serializable
data class ModelPricingDto(
    val input: String = "",
    val output: String = "",
    val free: Boolean = false,
)

/** `GET /api/model/info`（`hermes_cli/web_routers/models.py::get_model_info`）。 */
@Serializable
data class ModelInfoDto(
    val model: String? = null,
    val provider: String? = null,
    @SerialName("effective_context_length") val effectiveContextLength: Long? = null,
)

/** 一个可选项：provider + 模型名 + 该模型是否支持推理强度。 */
data class ModelEntry(
    val provider: String,
    val providerName: String,
    val model: String,
    val reasoning: Boolean,
    val free: Boolean,
)

/** 一个 provider 分组。 */
data class ModelProviderGroup(
    val slug: String,
    val name: String,
    val models: List<ModelEntry>,
    val authenticated: Boolean,
    val warning: String?,
    val isCurrent: Boolean,
)

/** 展平后的目录：菜单直接遍历 [groups]，找当前项用 [entryOf]。 */
data class ModelCatalog(
    val groups: List<ModelProviderGroup>,
    val currentModel: String?,
    val currentProvider: String?,
) {
    val isEmpty: Boolean get() = groups.all { it.models.isEmpty() }

    fun entryOf(provider: String?, model: String?): ModelEntry? {
        if (model.isNullOrBlank()) return null
        val group = groups.firstOrNull { it.slug.equals(provider, ignoreCase = true) }
            ?: groups.firstOrNull { g -> g.models.any { it.model == model } }
            ?: return null
        return group.models.firstOrNull { it.model == model }
    }

    /** 当前模型是否支持推理强度；目录没数据时按"支持"处理（菜单仍可开）。 */
    fun reasoningSupported(provider: String?, model: String?): Boolean =
        entryOf(provider, model)?.reasoning ?: true

    companion object {
        val EMPTY = ModelCatalog(groups = emptyList(), currentModel = null, currentProvider = null)
    }
}

fun ModelOptionsDto.toCatalog(): ModelCatalog = ModelCatalog(
    groups = providers.map { provider ->
        val caps = provider.capabilities.orEmpty()
        val pricing = provider.pricing.orEmpty()
        ModelProviderGroup(
            slug = provider.slug,
            name = provider.name.ifBlank { provider.slug },
            models = provider.models.map { model ->
                ModelEntry(
                    provider = provider.slug,
                    providerName = provider.name.ifBlank { provider.slug },
                    model = model,
                    reasoning = caps[model]?.reasoning ?: true,
                    free = pricing[model]?.free ?: false,
                )
            },
            authenticated = provider.authenticated != false,
            warning = provider.warning?.takeIf { it.isNotBlank() },
            isCurrent = provider.isCurrent == true,
        )
    },
    currentModel = model?.takeIf { it.isNotBlank() },
    currentProvider = provider?.takeIf { it.isNotBlank() },
)

/**
 * 推理强度阶梯（`agent/reasoning_effort.py::EFFORT_LADDER`，低 → 高）。
 *
 * 后端内部阶梯比任何 provider 的 wire 词表都宽，实际下发会被 clamp 到路由支持的最大值；
 * 这里只用于菜单展示与选择。
 */
val REASONING_EFFORTS: List<String> = listOf(
    "none",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
)

/** 菜单与 pill 上的短标签（官方桌面端用 Low / Med / High 这套缩写）。 */
fun reasoningEffortLabel(effort: String?): String = when (effort?.lowercase()) {
    "none" -> "Off"
    "minimal" -> "Min"
    "low" -> "Low"
    "medium" -> "Med"
    "high" -> "High"
    "xhigh" -> "XHigh"
    "max" -> "Max"
    "ultra" -> "Ultra"
    null, "" -> "Med"
    else -> effort
}
