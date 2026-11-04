package dev.shinglem.hermes.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/api/providers/custom-endpoints` 的一个自定义端点（OpenAI 兼容）。
 *
 * 后端会把 v12 的 `providers.<key>` 与历史 `custom_providers:` 列表合并成同一份响应，
 * `source` 说明这一行来自哪种形状 —— 删除和激活都按 `id` 走，不需要前端区分。
 */
@Serializable
data class CustomEndpointDto(
    val id: String = "",
    val name: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    @SerialName("api_mode") val apiMode: String = "",
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("discover_models") val discoverModels: Boolean = true,
    @SerialName("has_api_key") val hasApiKey: Boolean = false,
    /** 后端只回预览/环境变量引用，不会回明文。 */
    @SerialName("api_key_preview") val apiKeyPreview: String = "",
    @SerialName("is_current") val isCurrent: Boolean = false,
    val source: String = "",
)

/** `GET /api/providers/custom-endpoints` 的完整响应。 */
@Serializable
data class CustomEndpointsDto(
    val endpoints: List<CustomEndpointDto> = emptyList(),
    val current: CurrentModelDto = CurrentModelDto(),
)

/** 主模型槽位当前指向谁（删除端点后后端只清 provider/base_url，model 会留着）。 */
@Serializable
data class CurrentModelDto(
    val provider: String = "",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
)

/**
 * 写入自定义端点的请求体（对应后端 `CustomEndpointUpdate`）。
 *
 * `id` 为空 = 新建，由后端按 `name` 生成 slug；`apiKey` 为 null = 保留已存的键，
 * 空串 = 清空（后端语义，不要用空串表示"不改"）。
 */
@Serializable
data class CustomEndpointUpdateDto(
    val id: String = "",
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("api_mode") val apiMode: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("discover_models") val discoverModels: Boolean = true,
    @SerialName("make_default") val makeDefault: Boolean = false,
    val models: List<String>? = null,
)

/** `POST /api/providers/custom-endpoints/validate`：探测 `/models` 与真实传输路由。 */
@Serializable
data class CustomEndpointProbeDto(
    val ok: Boolean = false,
    val reachable: Boolean = false,
    val message: String = "",
    val models: List<String> = emptyList(),
    @SerialName("transport_checked") val transportChecked: String? = null,
    /** 真正供出 `/models` 的 base —— 后端会把保存的 URL 改成它，这里只用来提示。 */
    @SerialName("resolved_base_url") val resolvedBaseUrl: String? = null,
)

/** `POST /api/providers/custom-endpoints/{id}/activate` 的响应。 */
@Serializable
data class CustomEndpointActivatedDto(
    val ok: Boolean = false,
    val provider: String = "",
    val model: String = "",
)

/** 保存自定义端点的响应 = 端点列表 + `ok` / `id`。 */
@Serializable
data class CustomEndpointSavedDto(
    val ok: Boolean = false,
    val id: String = "",
    val endpoints: List<CustomEndpointDto> = emptyList(),
    val current: CurrentModelDto = CurrentModelDto(),
)
