package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/model/info`：当前配置的模型与上下文长度（自动探测 vs 覆盖）。 */
@Serializable
data class ModelInfoAdminDto(
    val model: String = "",
    val provider: String = "",
    @SerialName("auto_context_length") val autoContextLength: Long = 0,
    @SerialName("config_context_length") val configContextLength: Long = 0,
    @SerialName("effective_context_length") val effectiveContextLength: Long = 0,
) {
    val label: String
        get() = when {
            provider.isNotBlank() && model.isNotBlank() -> "$provider / $model"
            model.isNotBlank() -> model
            else -> ""
        }
}

/**
 * `POST /api/model/set` 的回执。
 *
 * `confirmRequired=true` 表示目标模型价格偏高，需要带 `confirm_expensive_model` 重发；
 * `staleAux` 表示辅助模型槽位里有指向旧模型的钉子（后端会提示但不会自动改）。
 */
@Serializable
data class ModelSetResultDto(
    val ok: Boolean = false,
    val scope: String = "",
    val provider: String = "",
    val model: String = "",
    @SerialName("confirm_required") val confirmRequired: Boolean = false,
    @SerialName("confirm_message") val confirmMessage: String = "",
    @SerialName("stale_aux") val staleAux: List<String> = emptyList(),
)

/** `GET /api/model/auxiliary` 的一个任务槽位。 */
@Serializable
data class AuxTaskDto(
    val task: String = "",
    val provider: String = "auto",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** 指向本地/LAN 端点（与"忘了改的付费 provider 钉子"区分开）。 */
    @SerialName("local_endpoint") val localEndpoint: Boolean = false,
    /** 插件注册的任务才有：显示名 / 说明 / 来源插件 / 继承自哪个内建槽位。 */
    val label: String = "",
    val hint: String = "",
    val plugin: String = "",
    @SerialName("inherit_from") val inheritFrom: String? = null,
    val effective: AuxEffectiveDto? = null,
) {
    /** `auto` 表示跟随主模型。 */
    val isAuto: Boolean get() = provider.equals("auto", ignoreCase = true) && model.isBlank()
}

@Serializable
data class AuxEffectiveDto(
    val provider: String = "",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
)

@Serializable
data class AuxMainDto(
    val provider: String = "",
    val model: String = "",
)

@Serializable
data class AuxModelsDto(
    val tasks: List<AuxTaskDto> = emptyList(),
    val main: AuxMainDto = AuxMainDto(),
)
