package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/profiles` 的一项（REST 视角，字段比 `profiles.list` 的 RPC 视图多）。
 *
 * 左 rail 用的是 RPC `profiles.list`（只要名字/模型/技能数），设置中心的「配置档案」页用这份
 * —— 它还要 `path`（给用户看落盘位置）、`has_env`（有没有独立凭据）、`gateway_running`。
 */
@Serializable
data class ProfileAdminDto(
    val name: String = "",
    val path: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("has_env") val hasEnv: Boolean = false,
    @SerialName("skill_count") val skillCount: Int = 0,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    val description: String = "",
    @SerialName("description_auto") val descriptionAuto: Boolean = false,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("bot_title") val botTitle: String = "",
    @SerialName("distribution_name") val distributionName: String? = null,
    @SerialName("distribution_version") val distributionVersion: String? = null,
    @SerialName("distribution_source") val distributionSource: String? = null,
    @SerialName("has_alias") val hasAlias: Boolean = false,
) {
    val label: String get() = displayName.ifBlank { name }

    /** `provider/model`；都没配时给空串。 */
    val modelLabel: String
        get() = when {
            !provider.isNullOrBlank() && !model.isNullOrBlank() -> "$provider / $model"
            !model.isNullOrBlank() -> model.orEmpty()
            else -> ""
        }
}

@Serializable
data class ProfilesAdminDto(
    val profiles: List<ProfileAdminDto> = emptyList(),
)

/** `GET /api/profiles/{name}/soul`：`exists=false` 表示这个档案还没有 SOUL.md。 */
@Serializable
data class ProfileSoulDto(
    val content: String = "",
    val exists: Boolean = false,
)

/** `POST /api/profiles` 的回执（部分字段是 best-effort，失败会有 `model_error`）。 */
@Serializable
data class ProfileCreateResultDto(
    val ok: Boolean = false,
    val name: String = "",
    val path: String = "",
    @SerialName("model_set") val modelSet: Boolean = false,
    @SerialName("model_error") val modelError: String = "",
    @SerialName("soul_set") val soulSet: Boolean = false,
)

/** `POST /api/profiles/{name}/export` 的回执：`path` 是生成的归档。 */
@Serializable
data class ProfileExportResultDto(
    val ok: Boolean = false,
    val path: String = "",
)
