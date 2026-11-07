package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.config.setJsonAt
import dev.shinglem.hermes.core.transport.RestClient
import dev.shinglem.hermes.core.transport.RestMethod
import dev.shinglem.hermes.core.transport.Timeouts
import dev.shinglem.hermes.core.transport.withProfileQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 后端 `config.yaml` 的读改（`GET` / `PUT /api/config`）。
 *
 * 与本地设置（`core.config.SettingsStore` 写 `*_custom.json`）完全是两套东西：
 * 这里是**后端**的配置，改完立即对后端生效。
 *
 * 写操作只发稀疏 diff —— 后端做 deep-merge，整份回写会把别的页面刚改的键覆盖回旧值。
 */
class ConfigRepository(
    private val rest: RestClient,
) {
    suspend fun read(profile: String?): JsonObject {
        val element = rest.get(withProfileQuery("/api/config", profile), Timeouts.CATALOG_MS)
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    suspend fun patch(profile: String?, patch: JsonObject) {
        if (patch.isEmpty()) return
        val body = buildJsonObject {
            put("config", patch)
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        rest.send(RestMethod.PUT, withProfileQuery("/api/config", profile), body, Timeouts.CATALOG_MS)
    }
}

/** 按点分路径读一个标量，转成表单里的文本。 */
internal fun JsonObject.readPath(path: String): String {
    var node: JsonElement = this
    for (segment in path.split('.')) {
        node = (node as? JsonObject)?.get(segment) ?: return ""
    }
    return when (node) {
        is JsonPrimitive -> if (node.isString) node.content else node.content
        is kotlinx.serialization.json.JsonArray -> node.joinToString(",") { it.toString().trim('"') }
        else -> ""
    }
}

/** 把表单里的文本按点分路径写成稀疏 patch（数字/布尔按 [kind] 转）。 */
internal fun buildConfigPatch(edits: Map<String, String>, specs: List<ConfigFieldSpec>): JsonObject {
    var patch: JsonObject = JsonObject(emptyMap())
    for (spec in specs) {
        val raw = edits[spec.path] ?: continue
        val element = when (spec.kind) {
            ConfigFieldKind.BOOLEAN -> JsonPrimitive(raw.equals("true", ignoreCase = true))
            ConfigFieldKind.NUMBER -> raw.trim().toLongOrNull()?.let { JsonPrimitive(it) } ?: continue
            ConfigFieldKind.ENUM -> spec.enumValues.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }
                ?.let { JsonPrimitive(it) } ?: continue
            ConfigFieldKind.TEXT -> JsonPrimitive(raw)
            ConfigFieldKind.LIST -> JsonPrimitive(raw)
        }
        patch = setJsonAt(patch, spec.path, element)
    }
    return patch
}

enum class ConfigFieldKind { BOOLEAN, NUMBER, ENUM, TEXT, LIST }

/** 一个后端配置项：点分路径 + 控件类型。 */
data class ConfigFieldSpec(
    val path: String,
    val label: String,
    val kind: ConfigFieldKind,
    val hint: String? = null,
    val enumValues: List<String> = emptyList(),
)

/** 安全 · 审批页的字段（与后端 `VALID_APPROVAL_MODES` 一致）。 */
val APPROVAL_FIELDS = listOf(
    ConfigFieldSpec(
        path = "approvals.mode",
        label = "审批模式",
        kind = ConfigFieldKind.ENUM,
        enumValues = listOf("manual", "smart", "off"),
        hint = "manual = 每个危险动作都要确认；smart = 由策略判断；off = 不拦（慎用）",
    ),
    ConfigFieldSpec(
        path = "approvals.timeout",
        label = "审批超时（秒）",
        kind = ConfigFieldKind.NUMBER,
        hint = "超时未确认按拒绝处理",
    ),
    ConfigFieldSpec(
        path = "approvals.mcp_reload_confirm",
        label = "重载 MCP 前确认",
        kind = ConfigFieldKind.BOOLEAN,
    ),
    ConfigFieldSpec(
        path = "command_allowlist",
        label = "命令白名单",
        kind = ConfigFieldKind.LIST,
        hint = "逗号分隔的命令前缀；命中后不再逐次审批",
    ),
)

/** 安全 · 隐私与网络。 */
val PRIVACY_FIELDS = listOf(
    ConfigFieldSpec(
        path = "security.redact_secrets",
        label = "日志脱敏",
        kind = ConfigFieldKind.BOOLEAN,
        hint = "把疑似密钥的内容在日志与工具输出里打码",
    ),
    ConfigFieldSpec(
        path = "security.allow_private_urls",
        label = "允许访问内网地址",
        kind = ConfigFieldKind.BOOLEAN,
        hint = "关闭后工具不能访问 127.0.0.1 / 局域网地址（SSRF 防护）",
    ),
)

/** 安全 · 检查点。 */
val CHECKPOINT_FIELDS = listOf(
    ConfigFieldSpec(
        path = "checkpoints.enabled",
        label = "启用检查点",
        kind = ConfigFieldKind.BOOLEAN,
        hint = "对文件改动做快照，可回滚；对新会话生效",
    ),
)

/**
 * 智能体 · 记忆页的「内置层」配置（`config.memory`）。
 *
 * 这四项只影响内置的 `MEMORY.md` / `USER.md`，与外部 provider 无关；
 * 后端字段名见 `/api/config` 的 `memory` 段（默认 2200 / 1375）。
 */
val MEMORY_FIELDS = listOf(
    ConfigFieldSpec(
        path = "memory.memory_enabled",
        label = "持久记忆",
        kind = ConfigFieldKind.BOOLEAN,
        hint = "保存有助于未来会话的持久记忆",
    ),
    ConfigFieldSpec(
        path = "memory.user_profile_enabled",
        label = "用户画像",
        kind = ConfigFieldKind.BOOLEAN,
        hint = "维护一份精简的用户偏好画像",
    ),
    ConfigFieldSpec(
        path = "memory.memory_char_limit",
        label = "记忆预算",
        kind = ConfigFieldKind.NUMBER,
        hint = "注入 MEMORY.md 的字符上限（官方默认 2200）",
    ),
    ConfigFieldSpec(
        path = "memory.user_char_limit",
        label = "画像预算",
        kind = ConfigFieldKind.NUMBER,
        hint = "注入 USER.md 的字符上限（官方默认 1375）",
    ),
)

/** 定位某个安全页的字段清单。 */
fun securityFieldsOf(tab: SecurityTab): List<ConfigFieldSpec> = when (tab) {
    SecurityTab.APPROVALS -> APPROVAL_FIELDS
    SecurityTab.PRIVACY -> PRIVACY_FIELDS
    SecurityTab.CHECKPOINTS -> CHECKPOINT_FIELDS
}

enum class SecurityTab { APPROVALS, PRIVACY, CHECKPOINTS }

/** 备用模型的一条（主模型不可用 / 额度耗尽时按顺序回退）。 */
data class FallbackEntry(val provider: String, val model: String) {
    val label: String get() = if (provider.isBlank()) model else "$provider / $model"
}

/**
 * 解析 `fallback_providers`。
 *
 * 后端 `_fallback_entries` 同时认**单个 dict** 与 **list**（历史形态），所以两种都接；
 * 返回的布尔表示"磁盘上是 list 形状"，写回时要原样保持。
 */
internal fun readFallbackProviders(config: JsonObject): Pair<List<FallbackEntry>, Boolean> {
    val node = config["fallback_providers"]
    val items: List<JsonElement> = when (node) {
        is JsonArray -> node.toList()
        is JsonObject -> listOf(node)
        else -> emptyList()
    }
    val entries = items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val provider = (obj["provider"] as? JsonPrimitive)?.content.orEmpty()
        val model = (obj["model"] as? JsonPrimitive)?.content.orEmpty()
        if (provider.isBlank() || model.isBlank()) null else FallbackEntry(provider, model)
    }
    return entries to (node is JsonArray)
}

/**
 * 写回 `fallback_providers`，保持磁盘形状：原来是 list 才写 list，否则写单个 dict
 * （单 dict 是后端的原生形态）。空列表 = `[]`，表示清空回退链。
 */
internal fun fallbackPatch(entries: List<FallbackEntry>, keepArrayShape: Boolean): JsonObject {
    val items = entries.map { entry ->
        buildJsonObject {
            put("provider", entry.provider)
            put("model", entry.model)
        }
    }
    val value: JsonElement = when {
        entries.isEmpty() -> JsonArray(emptyList())
        keepArrayShape || entries.size > 1 -> JsonArray(items)
        else -> items.first()
    }
    return buildJsonObject { put("fallback_providers", value) }
}

