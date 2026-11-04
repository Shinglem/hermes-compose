package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MoA 里的一个模型槽位（参考模型 / 聚合模型同形）。 */
@Serializable
data class MoaSlotDto(
    val provider: String = "",
    val model: String = "",
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val enabled: Boolean = true,
)

/**
 * 一个 MoA 预设。
 *
 * 后端读时会把不完整的预设"修"成默认值，写时却会 422 拒绝 —— 所以半填的预设
 * 必须在客户端拦住，不能发出去（否则是静默数据丢失，见上游 #64156）。
 */
@Serializable
data class MoaPresetDto(
    val enabled: Boolean = true,
    @SerialName("reference_models") val referenceModels: List<MoaSlotDto> = emptyList(),
    val aggregator: MoaSlotDto = MoaSlotDto(),
    /** null = 不传温度，用提供方默认值。 */
    @SerialName("reference_temperature") val referenceTemperature: Double? = null,
    @SerialName("aggregator_temperature") val aggregatorTemperature: Double? = null,
    /** null = 继承 `auxiliary.moa_reference.timeout`。 */
    @SerialName("reference_timeout") val referenceTimeout: Double? = null,
    /** `loud` = 失败的顾问要在回答里说明；`silent` = 静默降级。 */
    @SerialName("degraded_reference_policy") val degradedReferencePolicy: String = "loud",
    /** `user_turn` | `per_iteration` | `every_n:<N>`。 */
    val fanout: String = "user_turn",
)

/**
 * `GET /api/model/moa` 的响应。
 *
 * 除命名预设外，后端还回一份「默认预设的扁平镜像」（`reference_models` 等），
 * 那是给老客户端的兼容视图 —— 桌面端只用 [presets]，避免两份数据打架。
 */
@Serializable
data class MoaConfigDto(
    @SerialName("default_preset") val defaultPreset: String = "default",
    /** 正在生效的预设；空 = 用 [defaultPreset]。 */
    @SerialName("active_preset") val activePreset: String = "",
    val presets: Map<String, MoaPresetDto> = emptyMap(),
    /** `""` / `display` / `full`，由后端维护，桌面端只读。 */
    @SerialName("privacy_filter") val privacyFilter: String = "",
)

/**
 * `PUT /api/model/moa` 的请求体。
 *
 * **必须提交完整的 [presets] map**：后端把提交的命名 map 当作权威，没列出的预设会被删掉。
 */
@Serializable
data class MoaConfigUpdateDto(
    @SerialName("default_preset") val defaultPreset: String,
    @SerialName("active_preset") val activePreset: String,
    val presets: Map<String, MoaPresetDto>,
)
