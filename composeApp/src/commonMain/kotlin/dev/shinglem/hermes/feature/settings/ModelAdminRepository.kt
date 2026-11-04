package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.AuxModelsDto
import dev.shinglem.hermes.core.contract.ModelInfoAdminDto
import dev.shinglem.hermes.core.contract.ModelSetResultDto
import dev.shinglem.hermes.core.contract.MoaConfigDto
import dev.shinglem.hermes.core.contract.MoaConfigUpdateDto
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 模型配置页的数据源：`/api/model` 与 `/api/config`。
 *
 * 与 `feature.model.ModelRepository` 分工：那边是"当前会话的模型切换"（走 RPC `config.set`，
 * 只影响一个会话）；这里是"profile 的默认模型"（`POST /api/model/set`，写后端 config.yaml，
 * 对**新会话**生效）。两者不能混用 —— 页面上写的值要能在下一次开新会话时体现。
 */
class ModelAdminRepository(
    private val rest: RestClient,
) {
    suspend fun info(profile: String?): ModelInfoAdminDto {
        val element = rest.get(withProfileQuery("/api/model/info", profile), Timeouts.WRITE_MS)
        return RpcJson.json.decodeFromJsonElement(ModelInfoAdminDto.serializer(), element)
    }

    /**
     * 把模型钉到主槽位（`scope="main"`）或辅助任务槽位（`scope="auxiliary"` + [task]）。
     *
     * 返回 `confirm_required` 时必须由 UI 二次确认后用 `confirmExpensive=true` 重发，
     * 不能静默忽略 —— 后端是在拦"贵模型"。
     */
    suspend fun assignModel(
        profile: String?,
        scope: String,
        provider: String,
        model: String,
        task: String? = null,
        confirmExpensive: Boolean = false,
    ): ModelSetResultDto {
        val body = buildJsonObject {
            put("scope", scope)
            put("provider", provider)
            put("model", model)
            task?.takeIf { it.isNotBlank() }?.let { put("task", it) }
            if (confirmExpensive) put("confirm_expensive_model", true)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/model/set", profile),
            body,
            Timeouts.CATALOG_MS,
        )
        return RpcJson.json.decodeFromJsonElement(ModelSetResultDto.serializer(), element)
    }

    /** 重置全部辅助模型槽位（后端约定的哨兵 `task="__reset__"`）。 */
    suspend fun resetAuxiliary(profile: String?): ModelSetResultDto = assignModel(
        profile = profile,
        scope = "auxiliary",
        provider = "auto",
        model = "",
        task = RESET_AUX_TASK,
    )

    suspend fun auxiliary(profile: String?): AuxModelsDto {
        val element = rest.get(withProfileQuery("/api/model/auxiliary", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(AuxModelsDto.serializer(), element)
    }

    suspend fun moa(profile: String?): MoaConfigDto {
        val element = rest.get(withProfileQuery("/api/model/moa", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(MoaConfigDto.serializer(), element)
    }

    /** 保存 MoA 配置；`422` 表示提交里有半填/非法预设（客户端应当先自检）。 */
    suspend fun saveMoa(profile: String?, update: MoaConfigUpdateDto): MoaConfigDto {
        val element = rest.send(
            RestMethod.PUT,
            withProfileQuery("/api/model/moa", profile),
            RpcJson.json.encodeToJsonElement(MoaConfigUpdateDto.serializer(), update).jsonObject,
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(MoaConfigDto.serializer(), element)
    }
}

/** 后端约定的"重置全部辅助槽位"哨兵。 */
internal const val RESET_AUX_TASK = "__reset__"

/**
 * 上下文覆盖的稀疏 patch。
 *
 * 键必须是**顶层** `model_context_length`（int，0 = 自动）—— 这是后端
 * `_denormalize_config_from_web` 约定的写入口；写成 `{model:{context_length}}` 虽然能靠
 * deep-merge 碰巧落盘，但绕过了它的"0 就删键 / 变更模型时重探 provider"逻辑。
 */
internal fun contextLengthPatch(length: Long?): JsonObject = buildJsonObject {
    put("model_context_length", JsonPrimitive(length?.takeIf { it > 0 } ?: 0))
}
