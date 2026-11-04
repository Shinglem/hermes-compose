package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/env` 的一项：后端 `.env` 里的一个键及其元数据。
 *
 * 后端返回的是 `{ "<ENV_KEY>": {...} }` 字典，不是数组。
 * `provider` / `provider_label` 来自统一 provider 目录 —— 桌面端按它分组，
 * 与 CLI `hermes model` 用的是同一套身份。
 */
@Serializable
data class EnvVarDto(
    @SerialName("is_set") val isSet: Boolean = false,
    @SerialName("redacted_value") val redactedValue: String? = null,
    val description: String = "",
    val url: String? = null,
    val category: String = "",
    @SerialName("is_password") val isPassword: Boolean = false,
    val provider: String = "",
    @SerialName("provider_label") val providerLabel: String = "",
    /**
     * 同一个键可能同时服务多个内建 provider 路由。
     *
     * 注意这是**对象数组**（`{provider, provider_label, description, url, primary}`），
     * 不是字符串数组 —— 按 `List<String>` 解会在这 4 个键上直接抛 `Protocol` 错
     * （DASHSCOPE_API_KEY / COMMANDCODE_API_KEY / ALIBABA_TOKEN_PLAN_API_KEY /
     * ALIBABA_CODING_PLAN_API_KEY）。
     */
    @SerialName("provider_profiles") val providerProfiles: List<ProviderProfileDto> = emptyList(),
    /** 该 provider 的 0 号凭据键：卡片的主输入框用它。 */
    @SerialName("provider_primary") val providerPrimary: Boolean = false,
    val advanced: Boolean = false,
    /** 消息平台凭据：由「消息平台」页管理，这里隐藏，避免两处都改同一个键。 */
    @SerialName("channel_managed") val channelManaged: Boolean = false,
    /** `.env` 里用户自己加的、不在任何目录里的键。 */
    val custom: Boolean = false,
)

/** 一个键在目录里映射到的 provider 路由（一个键可能同时服务多个）。 */
@Serializable
data class ProviderProfileDto(
    val provider: String = "",
    @SerialName("provider_label") val providerLabel: String = "",
    val description: String = "",
    val url: String? = null,
    val primary: Boolean = false,
)
