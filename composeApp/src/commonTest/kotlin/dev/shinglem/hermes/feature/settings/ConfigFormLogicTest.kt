package dev.shinglem.hermes.feature.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 安全页的读写逻辑：点分路径取值 + 稀疏 patch 构建（非法值不发）。 */
class ConfigFormLogicTest {

    private val config: JsonObject = Json.parseToJsonElement(
        """
        {
          "approvals": {"mode": "smart", "timeout": 300, "mcp_reload_confirm": true},
          "command_allowlist": ["git", "npm"],
          "security": {"redact_secrets": true, "allow_private_urls": false},
          "checkpoints": {"enabled": false}
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `readPath 取嵌套标量、布尔与数组`() {
        assertEquals("smart", config.readPath("approvals.mode"))
        assertEquals("300", config.readPath("approvals.timeout"))
        assertEquals("true", config.readPath("approvals.mcp_reload_confirm"))
        assertEquals("git,npm", config.readPath("command_allowlist"))
        assertEquals("", config.readPath("approvals.missing"))
        assertEquals("", config.readPath("nope.deep.path"))
    }

    @Test
    fun `patch 按点分路径嵌套，且只含改动过的字段`() {
        val patch = buildConfigPatch(
            edits = mapOf("approvals.mode" to "manual", "checkpoints.enabled" to "true"),
            specs = APPROVAL_FIELDS + CHECKPOINT_FIELDS,
        )

        assertEquals(setOf("approvals", "checkpoints"), patch.keys)
        assertEquals("manual", patch["approvals"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("true", patch["checkpoints"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非法枚举与非法数字被丢弃，不会写坏后端配置`() {
        val patch = buildConfigPatch(
            edits = mapOf("approvals.mode" to "yolo", "approvals.timeout" to "abc"),
            specs = APPROVAL_FIELDS,
        )
        assertTrue(patch.isEmpty())
    }

    @Test
    fun `数字与布尔按类型写入而不是字符串`() {
        val patch = buildConfigPatch(
            edits = mapOf("approvals.timeout" to "120", "security.redact_secrets" to "false"),
            specs = APPROVAL_FIELDS + PRIVACY_FIELDS,
        )
        assertEquals("120", patch["approvals"]!!.jsonObject["timeout"]!!.jsonPrimitive.content)
        assertEquals(false, patch["security"]!!.jsonObject["redact_secrets"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `每个安全页的字段都指向真实存在的后端键`() {
        SecurityTab.entries.forEach { tab ->
            val specs = securityFieldsOf(tab)
            assertTrue(specs.isNotEmpty(), "$tab 没有字段")
            specs.forEach { spec ->
                assertTrue(
                    config.readPath(spec.path).isNotEmpty(),
                    "$tab 的 ${spec.path} 在后端配置里取不到值",
                )
            }
        }
    }
}
