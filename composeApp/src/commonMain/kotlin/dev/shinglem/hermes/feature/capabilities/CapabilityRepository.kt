package dev.shinglem.hermes.feature.capabilities

import dev.shinglem.hermes.core.contract.AckDto
import dev.shinglem.hermes.core.contract.BlueprintDto
import dev.shinglem.hermes.core.contract.BlueprintsDto
import dev.shinglem.hermes.core.contract.CronJobCreate
import dev.shinglem.hermes.core.contract.CronJobDto
import dev.shinglem.hermes.core.contract.CronJobPatch
import dev.shinglem.hermes.core.contract.CronJobUpdate
import dev.shinglem.hermes.core.contract.CronRunDto
import dev.shinglem.hermes.core.contract.CronRunsDto
import dev.shinglem.hermes.core.contract.DeliveryTargetDto
import dev.shinglem.hermes.core.contract.DeliveryTargetsDto
import dev.shinglem.hermes.core.contract.MessagingPlatformDto
import dev.shinglem.hermes.core.contract.MessagingPlatformUpdate
import dev.shinglem.hermes.core.contract.MessagingPlatformsDto
import dev.shinglem.hermes.core.contract.PlatformUpdateResult
import dev.shinglem.hermes.core.contract.SkillCatalogDto
import dev.shinglem.hermes.core.contract.SkillCatalogEntryDto
import dev.shinglem.hermes.core.contract.SkillContentDto
import dev.shinglem.hermes.core.contract.SkillDto
import dev.shinglem.hermes.core.contract.ToolsetDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 能力三页的数据源，全部走 REST `/api/...`（不在 RPC 表里）。
 *
 * 纪律：
 * - 读带 `?profile=`，与桌面端的全局 profile 切换保持一致；
 * - 写操作后由 ViewModel **回读**校验，不信任写接口的回执；
 * - 平台配置只提交用户填过的键（后端 `env` 是增量语义，空串视作清空）。
 */
class CapabilityRepository(
    private val rest: RestClient,
) {
    suspend fun skills(profile: String?): List<SkillDto> =
        decodeList(SkillDto.serializer(), rest.get(withProfile("/api/skills", profile)))

    suspend fun skillContent(profile: String?, name: String): String {
        val element = rest.get(withProfile("/api/skills/content", profile, "name" to name))
        return runCatching {
            RpcJson.json.decodeFromJsonElement(SkillContentDto.serializer(), element).content
        }.getOrDefault("")
    }

    suspend fun setSkillEnabled(profile: String?, name: String, enabled: Boolean): AckDto =
        sendAck(
            RestMethod.PUT,
            withProfile("/api/skills/toggle", profile),
            buildJson {
                put("name", name)
                put("enabled", enabled)
            },
        )

    /**
     * 官方可选技能库（`/api/skills/hub/official`）。
     *
     * 后端本地扫描 payload 里的 optional skills，**不联网**，所以离线也能看；
     * 每项带 `identifier`（安装用）与 `installed`（按 profile 判定）。
     */
    suspend fun skillCatalog(profile: String?): List<SkillCatalogEntryDto> {
        val element = rest.get(withProfile("/api/skills/hub/official", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(SkillCatalogDto.serializer(), element).skills
    }

    /**
     * 安装 / 卸载官方技能。
     *
     * 后端是**异步**起 CLI 动作的：回执只代表"已提交"，安装结果必须回读列表确认。
     */
    suspend fun installSkill(profile: String?, identifier: String): AckDto =
        sendAck(
            RestMethod.POST,
            withProfile("/api/skills/hub/install", profile),
            buildJson { put("identifier", identifier) },
            timeoutMs = Timeouts.CATALOG_MS,
        )

    suspend fun uninstallSkill(profile: String?, name: String): AckDto =
        sendAck(
            RestMethod.POST,
            withProfile("/api/skills/hub/uninstall", profile),
            buildJson { put("name", name) },
            timeoutMs = Timeouts.CATALOG_MS,
        )

    suspend fun toolsets(profile: String?): List<ToolsetDto> =
        decodeList(ToolsetDto.serializer(), rest.get(withProfile("/api/tools/toolsets", profile)))

    suspend fun setToolsetEnabled(profile: String?, name: String, enabled: Boolean): AckDto =
        sendAck(
            RestMethod.PUT,
            withProfile("/api/tools/toolsets/$name", profile),
            buildJson { put("enabled", enabled) },
        )

    suspend fun platforms(profile: String?): MessagingPlatformsDto {
        val element = rest.get(withProfile("/api/messaging/platforms", profile))
        return RpcJson.json.decodeFromJsonElement(MessagingPlatformsDto.serializer(), element)
    }

    /**
     * 保存平台配置。`enabled` 为 null 表示不动开关；`env` 只含用户实际填写的键。
     *
     * 返回 `{ok, platform, hot_served}`：**不是**平台快照，所以调用方必须回读列表。
     */
    suspend fun updatePlatform(
        profile: String?,
        platformId: String,
        enabled: Boolean?,
        env: Map<String, String>,
        clearEnv: List<String> = emptyList(),
    ): PlatformUpdateResult {
        val body = RpcJson.json.encodeToJsonElement(
            MessagingPlatformUpdate.serializer(),
            MessagingPlatformUpdate(
                enabled = enabled,
                env = env.filterValues { it.isNotBlank() },
                clearEnv = clearEnv,
                profile = profile,
            ),
        ).jsonObject
        val element = rest.send(
            RestMethod.PUT,
            withProfile("/api/messaging/platforms/$platformId", profile),
            body,
        )
        return runCatching {
            RpcJson.json.decodeFromJsonElement(PlatformUpdateResult.serializer(), element)
        }.getOrElse { PlatformUpdateResult(ok = true, platform = platformId) }
    }

    suspend fun testPlatform(profile: String?, platformId: String): AckDto =
        sendAck(RestMethod.POST, withProfile("/api/messaging/platforms/$platformId/test", profile))

    suspend fun cronJobs(profile: String?): List<CronJobDto> =
        decodeList(CronJobDto.serializer(), rest.get(withProfile("/api/cron/jobs", profile ?: "all")))

    suspend fun createCronJob(profile: String?, draft: CronJobCreate): AckDto =
        sendAck(
            RestMethod.POST,
            withProfile("/api/cron/jobs", profile),
            RpcJson.json.encodeToJsonElement(CronJobCreate.serializer(), draft).jsonObject,
        )

    /** 更新任务字段：后端收 `{updates: {...}}`，只提交改动过的项。 */
    suspend fun updateCronJob(profile: String?, jobId: String, patch: CronJobPatch): AckDto =
        sendAck(
            RestMethod.PUT,
            withProfile("/api/cron/jobs/$jobId", profile),
            RpcJson.json.encodeToJsonElement(CronJobUpdate.serializer(), CronJobUpdate(patch)).jsonObject,
        )

    /** 用蓝图建任务：后端按 `values` 填槽位并创建，返回新建的任务。 */
    suspend fun instantiateBlueprint(
        profile: String?,
        blueprint: String,
        values: Map<String, String>,
    ): AckDto = sendAck(
        RestMethod.POST,
        withProfile("/api/cron/blueprints/instantiate", profile, "profile" to (profile ?: "default")),
        buildJson {
            put("blueprint", blueprint)
            put(
                "values",
                kotlinx.serialization.json.JsonObject(
                    values.mapValues { (_, value) -> kotlinx.serialization.json.JsonPrimitive(value) },
                ),
            )
        },
    )

    suspend fun pauseCronJob(profile: String?, jobId: String): AckDto =
        sendAck(RestMethod.POST, withProfile("/api/cron/jobs/$jobId/pause", profile))

    suspend fun resumeCronJob(profile: String?, jobId: String): AckDto =
        sendAck(RestMethod.POST, withProfile("/api/cron/jobs/$jobId/resume", profile))

    suspend fun triggerCronJob(profile: String?, jobId: String): AckDto =
        sendAck(RestMethod.POST, withProfile("/api/cron/jobs/$jobId/trigger", profile))

    suspend fun deleteCronJob(profile: String?, jobId: String): AckDto =
        sendAck(RestMethod.DELETE, withProfile("/api/cron/jobs/$jobId", profile))

    suspend fun cronRuns(profile: String?, jobId: String, limit: Int = 10): List<CronRunDto> {
        val element = rest.get(
            withProfile("/api/cron/jobs/$jobId/runs", profile, "limit" to limit.toString()),
        )
        return if (element is JsonArray) {
            element.map { RpcJson.json.decodeFromJsonElement(CronRunDto.serializer(), it) }
        } else {
            RpcJson.json.decodeFromJsonElement(CronRunsDto.serializer(), element).runs
        }
    }

    suspend fun blueprints(profile: String?): List<BlueprintDto> {
        val element = rest.get(withProfile("/api/cron/blueprints", profile))
        return RpcJson.json.decodeFromJsonElement(BlueprintsDto.serializer(), element).blueprints
    }

    suspend fun deliveryTargets(profile: String?): List<DeliveryTargetDto> {
        val element = rest.get(withProfile("/api/cron/delivery-targets", profile))
        return RpcJson.json.decodeFromJsonElement(DeliveryTargetsDto.serializer(), element).targets
    }

    private suspend fun sendAck(
        method: RestMethod,
        path: String,
        body: JsonObject? = null,
        timeoutMs: Long = Timeouts.WRITE_MS,
    ): AckDto {
        val element = rest.send(method, path, body, timeoutMs)
        return runCatching {
            RpcJson.json.decodeFromJsonElement(AckDto.serializer(), element)
        }.getOrElse { AckDto(ok = true) }
    }

    /**
     * 列表解码：**不吞异常**。
     *
     * 这里曾经用 `runCatching {}.getOrDefault(emptyList())`，结果后端字段一变就静默显示"0 个技能"，
     * 排查时完全看不出是解析失败还是真的没有数据。解析问题必须冒泡成 `Loadable.Failed`。
     */
    private fun <T> decodeList(serializer: KSerializer<T>, element: JsonElement): List<T> {
        val array = element as? JsonArray
            ?: throw AppError.Protocol("/api/... 期望数组，收到 ${element::class.simpleName}")
        return array.map { item ->
            runCatching { RpcJson.json.decodeFromJsonElement(serializer, item) }
                .getOrElse { error ->
                    throw AppError.Protocol("列表项解析失败：${error.message?.take(160)}")
                }
        }
    }

    private fun withProfile(path: String, profile: String?, vararg extra: Pair<String, String>): String =
        withProfileQuery(path, profile, *extra)

    private fun buildJson(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        kotlinx.serialization.json.buildJsonObject(block)
}
