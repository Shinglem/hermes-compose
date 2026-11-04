package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.contract.ProfileAdminDto
import dev.shinglem.hermes.core.contract.ProfileCreateResultDto
import dev.shinglem.hermes.core.contract.ProfileExportResultDto
import dev.shinglem.hermes.core.contract.ProfileSoulDto
import dev.shinglem.hermes.core.contract.ProfilesAdminDto
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.RpcJson
import dev.shinglem.hermes.core.transport.Timeouts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * 配置档案的管理接口（`/api/profiles` 系列，全部 REST）。
 *
 * 注意注释里不要写 `斜杠星号` 这样的路径通配（Kotlin 块注释可嵌套，会吞掉后面整个文件）。
 *
 * 与左 rail 的 `feature.shell.ProfileRepository` 分工：那边走 RPC `profiles.list` 只取
 * 头像需要的字段；这里是设置中心的增删改查，需要 `path` / `has_env` / SOUL.md。
 *
 * 两条纪律：
 * - **导入收的是归档路径字符串**，不是字节流（计划 §6.3 易错点 1）；
 * - 每个写操作都由调用方回读列表，不信任回执。
 */
class ProfilesAdminRepository(
    private val rest: RestClient,
) {
    suspend fun list(): List<ProfileAdminDto> {
        val element = rest.get("/api/profiles", Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(ProfilesAdminDto.serializer(), element).profiles
    }

    suspend fun soul(name: String): ProfileSoulDto {
        val element = rest.get("/api/profiles/${encodeSegment(name)}/soul", Timeouts.CATALOG_MS)
        return RpcJson.json.decodeFromJsonElement(ProfileSoulDto.serializer(), element)
    }

    suspend fun saveSoul(name: String, content: String) {
        rest.send(
            RestMethod.PUT,
            "/api/profiles/${encodeSegment(name)}/soul",
            buildJsonObject { put("content", content) },
            Timeouts.CATALOG_MS,
        )
    }

    /**
     * 新建档案。
     *
     * `cloneFrom` 为 null 表示不克隆（干净档案 + 内置技能）；后端 `clone_from` 是显式来源，
     * 比老的 `clone_from_default` 语义清楚。
     */
    suspend fun create(name: String, cloneFrom: String?, soul: String?): ProfileCreateResultDto {
        val body = buildJsonObject {
            put("name", name)
            cloneFrom?.takeIf { it.isNotBlank() }?.let { put("clone_from", it) }
            soul?.let { put("soul", it) }
        }
        val element = rest.send(RestMethod.POST, "/api/profiles", body, Timeouts.CATALOG_MS)
        return runCatching {
            RpcJson.json.decodeFromJsonElement(ProfileCreateResultDto.serializer(), element)
        }.getOrDefault(ProfileCreateResultDto(name = name))
    }

    suspend fun rename(name: String, newName: String) {
        rest.send(
            RestMethod.PATCH,
            "/api/profiles/${encodeSegment(name)}",
            buildJsonObject { put("new_name", newName) },
            Timeouts.CATALOG_MS,
        )
    }

    suspend fun delete(name: String) {
        rest.send(RestMethod.DELETE, "/api/profiles/${encodeSegment(name)}", null, Timeouts.CATALOG_MS)
    }

    /** 导出：`output` 为空时由后端选临时路径，回执里的 `path` 才是最终位置。 */
    suspend fun export(name: String, output: String): ProfileExportResultDto {
        val element = rest.send(
            RestMethod.POST,
            "/api/profiles/${encodeSegment(name)}/export",
            buildJsonObject {
                put("extra_files", JsonObject(emptyMap()))
                put("output", output)
            },
            Timeouts.CATALOG_MS,
        )
        return runCatching {
            RpcJson.json.decodeFromJsonElement(ProfileExportResultDto.serializer(), element)
        }.getOrDefault(ProfileExportResultDto(path = output))
    }

    suspend fun import(archive: String, name: String?): String {
        val body = buildJsonObject {
            put("archive", archive)
            name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        }
        val element = rest.send(RestMethod.POST, "/api/profiles/import", body, Timeouts.CATALOG_MS)
        return runCatching {
            RpcJson.json.decodeFromJsonElement(ProfileImportResultDto.serializer(), element).name
        }.getOrDefault("")
    }
}

/** `POST /api/profiles/import`：`name` 是导入后的档案名（可能由归档顶层目录推断）。 */
@kotlinx.serialization.Serializable
data class ProfileImportResultDto(
    val ok: Boolean = false,
    val name: String = "",
)

/** 档案名规则（与后端一致）：小写字母/数字开头，只允许 `a-z0-9_-`，最长 64。 */
internal val PROFILE_NAME_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

/**
 * 把用户输入清洗成合法档案名：转小写、非法字符换成 `-`、去掉首尾分隔符、截断到 64。
 *
 * 只做"能输入什么"的兜底；最终仍以后端校验为准（它还会拒绝重名与保留名）。
 */
internal fun slugifyProfileName(raw: String): String = raw
    .trim()
    .lowercase()
    .map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-' }
    .joinToString("")
    .trim('-', '_')
    .take(64)

internal fun isProfileNameValid(raw: String): Boolean = PROFILE_NAME_REGEX.matches(raw)

/** 路径里的档案名要编码（档案名本身受上面正则约束，但仍防御性处理）。 */
private fun encodeSegment(value: String): String =
    dev.shinglem.hermes.core.transport.encodeQuery(value)
