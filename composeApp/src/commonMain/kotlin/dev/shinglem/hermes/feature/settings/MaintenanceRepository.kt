package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.ActionStatusDto
import dev.shinglem.hermes.core.contract.BackupStartedDto
import dev.shinglem.hermes.core.contract.CredentialPoolAddResultDto
import dev.shinglem.hermes.core.contract.CredentialPoolDto
import dev.shinglem.hermes.core.contract.CuratorStatusDto
import dev.shinglem.hermes.core.contract.FsTextDto
import dev.shinglem.hermes.core.contract.HooksDto
import dev.shinglem.hermes.core.contract.MemoryResetResultDto
import dev.shinglem.hermes.core.contract.MemoryStatusDto
import dev.shinglem.hermes.core.contract.ModelAnalyticsDto
import dev.shinglem.hermes.core.contract.OpsCheckpointsDto
import dev.shinglem.hermes.core.contract.PairingDto
import dev.shinglem.hermes.core.contract.SessionEmptyCountDto
import dev.shinglem.hermes.core.contract.SessionStatsDto
import dev.shinglem.hermes.core.contract.UsageAnalyticsDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 设置中心「维护」页组的数据源：记忆、后台动作、凭据池、用量、会话统计。
 *
 * 这些端点在 `hermes_cli/web_routers/{ops,analytics,sessions}.py` 里，与大模型/会话主链路
 * 无耦合，所以集中在一个 Repository —— 它们共享同一套「带 profile 直连 REST」的调用形状。
 */
class MaintenanceRepository(private val rest: RestClient) {

    // ---- 记忆 ----------------------------------------------------------

    suspend fun memoryStatus(profile: String?): MemoryStatusDto {
        val element = rest.get(withProfileQuery("/api/memory", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(MemoryStatusDto.serializer(), element)
    }

    /** [target] 取 `all` / `memory` / `user`；删除内置记忆文件，不可撤销。 */
    suspend fun resetMemory(target: String, profile: String?): MemoryResetResultDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/memory/reset", profile),
            buildJsonObject { put("target", target) },
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(MemoryResetResultDto.serializer(), element)
    }

    // ---- 后台动作（doctor / security-audit / backup / 检查点清理）----------

    /**
     * 启动一个后台动作。后端只回 `{ok,pid,name}`，日志要靠 [actionStatus] 轮询。
     *
     * [path] 必须是 `ops.py` 里 `_spawn_action` 挂过的端点之一。
     */
    private suspend fun startAction(path: String, profile: String?): BackupStartedDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery(path, profile),
            null,
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(BackupStartedDto.serializer(), element)
    }

    suspend fun runDoctor(profile: String?): BackupStartedDto = startAction("/api/ops/doctor", profile)

    suspend fun runSecurityAudit(profile: String?): BackupStartedDto =
        startAction("/api/ops/security-audit", profile)

    suspend fun runBackup(profile: String?): BackupStartedDto = startAction("/api/ops/backup", profile)

    suspend fun pruneCheckpoints(profile: String?): BackupStartedDto =
        startAction("/api/ops/checkpoints/prune", profile)

    suspend fun runCurator(profile: String?): BackupStartedDto = startAction("/api/curator/run", profile)

    suspend fun actionStatus(name: String, lines: Int = 200): ActionStatusDto {
        val element = rest.get("/api/actions/$name/status?lines=$lines", Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(ActionStatusDto.serializer(), element)
    }

    /** 轮询到动作结束（`running=false`），返回最后一次状态；超时也返回最后状态。 */
    suspend fun awaitAction(name: String, timeoutMs: Long = ACTION_TIMEOUT_MS): ActionStatusDto =
        withTimeoutOrNull(timeoutMs) {
            var last = actionStatus(name)
            while (last.running) {
                delay(ACTION_POLL_MS)
                last = actionStatus(name)
            }
            last
        } ?: actionStatus(name)

    suspend fun checkpoints(profile: String?): OpsCheckpointsDto {
        val element = rest.get(withProfileQuery("/api/ops/checkpoints", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(OpsCheckpointsDto.serializer(), element)
    }

    suspend fun curator(profile: String?): CuratorStatusDto {
        val element = rest.get(withProfileQuery("/api/curator", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(CuratorStatusDto.serializer(), element)
    }

    suspend fun setCuratorPaused(paused: Boolean, profile: String?): Boolean {
        val element = rest.send(
            RestMethod.PUT,
            withProfileQuery("/api/curator/paused", profile),
            buildJsonObject { put("paused", paused) },
            Timeouts.WRITE_MS,
        )
        return element.jsonObject["paused"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: paused
    }

    // ---- 凭据池 --------------------------------------------------------

    suspend fun credentialPool(profile: String?): CredentialPoolDto {
        val element = rest.get(withProfileQuery("/api/credentials/pool", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(CredentialPoolDto.serializer(), element)
    }

    suspend fun addCredential(
        provider: String,
        apiKey: String,
        label: String?,
        profile: String?,
    ): CredentialPoolAddResultDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/credentials/pool", profile),
            buildJsonObject {
                put("provider", provider)
                put("api_key", apiKey)
                label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
            },
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(CredentialPoolAddResultDto.serializer(), element)
    }

    suspend fun deleteCredential(provider: String, index: Int, profile: String?) {
        rest.send(
            RestMethod.DELETE,
            withProfileQuery("/api/credentials/pool/$provider/$index", profile),
            null,
            Timeouts.WRITE_MS,
        )
    }

    // ---- 用量 ----------------------------------------------------------

    suspend fun usage(days: Int, profile: String?): UsageAnalyticsDto {
        val element = rest.get(
            withProfileQuery("/api/analytics/usage", profile, "days" to days.toString()),
            Timeouts.CATALOG_MS,
        )
        return RpcJson.json.decodeFromJsonElement(UsageAnalyticsDto.serializer(), element)
    }

    // ---- 会话维护 ------------------------------------------------------

    suspend fun sessionStats(profile: String?): SessionStatsDto {
        val element = rest.get(withProfileQuery("/api/sessions/stats", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(SessionStatsDto.serializer(), element)
    }

    suspend fun emptySessionCount(profile: String?): Long {
        val element = rest.get(withProfileQuery("/api/sessions/empty/count", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(SessionEmptyCountDto.serializer(), element).count
    }

    /** 删除空会话；返回实际删除条数（后端回执里是 `deleted`）。 */
    suspend fun deleteEmptySessions(profile: String?): Int {
        val element: JsonElement = rest.send(
            RestMethod.DELETE,
            withProfileQuery("/api/sessions/empty", profile),
            null,
            Timeouts.WRITE_MS,
        )
        return element.jsonObject["deleted"]?.jsonPrimitive?.intOrNull ?: 0
    }

    // ---- 记忆文件内容（`/api/fs/read-text` / `write-text`，绝对路径）------

    suspend fun readTextFile(path: String, profile: String?): FsTextDto {
        val element = rest.get(
            withProfileQuery("/api/fs/read-text", profile, "path" to path),
            Timeouts.CATALOG_MS,
        )
        return RpcJson.json.decodeFromJsonElement(FsTextDto.serializer(), element)
    }

    suspend fun writeTextFile(path: String, content: String, profile: String?) {
        rest.send(
            RestMethod.POST,
            withProfileQuery("/api/fs/write-text", profile),
            buildJsonObject {
                put("path", path)
                put("content", content)
            },
            Timeouts.WRITE_MS,
        )
    }

    // ---- 备份：下载 / 恢复 ----------------------------------------------

    /** 下载服务端生成的备份 zip（`archive` 取备份动作回执里的路径）。 */
    suspend fun downloadBackup(archive: String, profile: String?): ByteArray =
        rest.download(withProfileQuery("/api/ops/backup/download", profile, "archive" to archive))

    /** 从备份恢复（`force=true`：后端以非交互方式跑 `hermes import`）。 */
    suspend fun importBackup(archive: String, force: Boolean, profile: String?): BackupStartedDto {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/ops/import", profile),
            buildJsonObject {
                put("archive", archive)
                put("force", force)
            },
            Timeouts.WRITE_MS,
        )
        return RpcJson.json.decodeFromJsonElement(BackupStartedDto.serializer(), element)
    }

    // ---- 诊断：dump / debug-share ---------------------------------------

    suspend fun runDump(profile: String?): BackupStartedDto = startAction("/api/ops/dump", profile)

    /** 生成调试分享链接（同步返回，不是后台动作）。 */
    suspend fun debugShare(lines: Int, redact: Boolean, profile: String?): JsonObject {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/ops/debug-share", profile),
            buildJsonObject {
                put("lines", lines)
                put("redact", redact)
            },
            Timeouts.CATALOG_MS,
        )
        return element.jsonObject
    }

    // ---- shell hooks ----------------------------------------------------

    suspend fun hooks(profile: String?): HooksDto {
        val element = rest.get(withProfileQuery("/api/ops/hooks", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(HooksDto.serializer(), element)
    }

    /** 新增一个钩子；`approve=true` 同时写入同意白名单（否则不会触发）。 */
    suspend fun addHook(
        event: String,
        command: String,
        matcher: String?,
        timeout: Int?,
        profile: String?,
    ) {
        rest.send(
            RestMethod.POST,
            withProfileQuery("/api/ops/hooks", profile),
            buildJsonObject {
                put("event", event)
                put("command", command)
                matcher?.takeIf { it.isNotBlank() }?.let { put("matcher", it) }
                timeout?.let { put("timeout", it) }
                put("approve", true)
            },
            Timeouts.WRITE_MS,
        )
    }

    suspend fun deleteHook(event: String, command: String, profile: String?) {
        rest.send(
            RestMethod.DELETE,
            withProfileQuery("/api/ops/hooks", profile),
            buildJsonObject {
                put("event", event)
                put("command", command)
            },
            Timeouts.WRITE_MS,
        )
    }

    // ---- 配对（远程管理端）----------------------------------------------

    suspend fun pairing(profile: String?): PairingDto {
        val element = rest.get(withProfileQuery("/api/pairing", profile), Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(PairingDto.serializer(), element)
    }

    suspend fun approvePairing(platform: String, requestId: String, profile: String?) {
        rest.send(
            RestMethod.POST,
            withProfileQuery("/api/pairing/approve", profile),
            buildJsonObject {
                put("platform", platform)
                put("request_id", requestId)
            },
            Timeouts.WRITE_MS,
        )
    }

    suspend fun revokePairing(platform: String, userId: String, profile: String?) {
        rest.send(
            RestMethod.POST,
            withProfileQuery("/api/pairing/revoke", profile),
            buildJsonObject {
                put("platform", platform)
                put("user_id", userId)
            },
            Timeouts.WRITE_MS,
        )
    }

    // ---- 用量：按模型卡片 -----------------------------------------------

    suspend fun modelAnalytics(days: Int, profile: String?): ModelAnalyticsDto {
        val element = rest.get(
            withProfileQuery("/api/analytics/models", profile, "days" to days.toString()),
            Timeouts.CATALOG_MS,
        )
        return RpcJson.json.decodeFromJsonElement(ModelAnalyticsDto.serializer(), element)
    }

    // ---- 会话：按条件清理 / 导入 ----------------------------------------

    /** 按条件清理会话；`dryRun=true` 只回统计（确认框用它预览）。 */
    suspend fun pruneSessions(olderThanDays: Int, dryRun: Boolean, profile: String?): JsonObject {
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/sessions/prune", profile),
            buildJsonObject {
                put("older_than_days", olderThanDays)
                put("dry_run", dryRun)
            },
            Timeouts.CATALOG_MS,
        )
        return element.jsonObject
    }

    /**
     * 会话导入：原样提交 JSON。
     *
     * 后端要 `{sessions: [...]}`（会话行，不是消息列表）；传入数组时自动包一层。
     */
    suspend fun importSessions(raw: String, profile: String?): JsonObject {
        val parsed = RpcJson.json.parseToJsonElement(raw)
        val body = when (parsed) {
            is JsonObject -> parsed
            is JsonArray -> buildJsonObject { put("sessions", parsed) }
            else -> throw AppError.Unknown("会话 JSON 必须是对象或数组")
        }
        val element = rest.send(
            RestMethod.POST,
            withProfileQuery("/api/sessions/import", profile),
            body,
            Timeouts.CATALOG_MS,
        )
        return element.jsonObject
    }

    private companion object {
        const val ACTION_POLL_MS = 700L
        const val ACTION_TIMEOUT_MS = 180_000L
    }
}