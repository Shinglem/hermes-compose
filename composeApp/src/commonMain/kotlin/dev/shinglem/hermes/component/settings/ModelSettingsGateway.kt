package dev.shinglem.hermes.component.settings

import dev.shinglem.hermes.core.contract.AuxModelsDto
import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.core.contract.ModelSetResultDto
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.core.contract.ModelInfoAdminDto
import kotlinx.serialization.json.JsonObject

/**
 * 模型配置页需要的后端动作。
 *
 * 这个域是三处来源的合流（迁移前 `ModelSettingsViewModel` 同时吃三个 Repository）：
 * - `models.options` —— 模型目录（RPC，带 1h 磁盘缓存）；
 * - `admin.*` —— profile 默认模型 / 辅助模型（REST）；
 * - `config.read/patch` —— 上下文覆盖与备用模型链（REST，写 `config.yaml`）。
 *
 * 合成一个接口而非三个参数：Component 的构造签名会干净很多，测试也只需实现一个 fake。
 */
interface ModelSettingsGateway {
    suspend fun info(profile: String?): ModelInfoAdminDto

    suspend fun options(profile: String?, refresh: Boolean = false): ModelCatalog

    suspend fun auxiliary(profile: String?): AuxModelsDto

    suspend fun assignModel(
        profile: String?,
        scope: String,
        provider: String,
        model: String,
        task: String?,
        confirmExpensive: Boolean = false,
    ): ModelSetResultDto

    suspend fun resetAuxiliary(profile: String?): ModelSetResultDto

    suspend fun readConfig(profile: String?): JsonObject

    suspend fun patchConfig(profile: String?, patch: JsonObject)
}

/** 生产适配。S3 后删除。 */
class ConfigModelSettingsGateway(
    private val models: dev.shinglem.hermes.feature.model.ModelRepository,
    private val admin: dev.shinglem.hermes.feature.settings.ModelAdminRepository,
    private val config: dev.shinglem.hermes.feature.settings.ConfigRepository,
) : ModelSettingsGateway {

    override suspend fun info(profile: String?): ModelInfoAdminDto = admin.info(profile)

    override suspend fun options(profile: String?, refresh: Boolean): ModelCatalog =
        models.options(profile, refresh)

    override suspend fun auxiliary(profile: String?): AuxModelsDto = admin.auxiliary(profile)

    override suspend fun assignModel(
        profile: String?,
        scope: String,
        provider: String,
        model: String,
        task: String?,
        confirmExpensive: Boolean,
    ): ModelSetResultDto = admin.assignModel(profile, scope, provider, model, task, confirmExpensive)

    override suspend fun resetAuxiliary(profile: String?) = admin.resetAuxiliary(profile)

    override suspend fun readConfig(profile: String?): JsonObject = config.read(profile)

    override suspend fun patchConfig(profile: String?, patch: JsonObject) = config.patch(profile, patch)
}
