package dev.shinglem.hermes.core.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import dev.shinglem.hermes.core.config.AutostartMode
import dev.shinglem.hermes.core.config.BackendMode
import dev.shinglem.hermes.core.config.ElevateMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String): JsonObject = ConfigJson.parseToJsonElement(text) as JsonObject

class JsonMergeTest {
    @Test
    fun `对象递归合并，未提到的键保持基础值`() {
        val base = json("""{"backend":{"remote":{"base_url":"http://a","connect_timeout_ms":1000}}}""")
        val over = json("""{"backend":{"remote":{"base_url":"http://b"}}}""")
        val merged = deepMergeJson(base, over)
        assertEquals(
            """{"backend":{"remote":{"base_url":"http://b","connect_timeout_ms":1000}}}""",
            merged.toString().replace("\\s".toRegex(), ""),
        )
    }

    @Test
    fun `数组整体替换而不是拼接`() {
        val base = json("""{"backend":{"local":{"extra_args":["--skip-build"]}}}""")
        val over = json("""{"backend":{"local":{"extra_args":[]}}}""")
        val merged = deepMergeJson(base, over)
        val args = (merged["backend"] as JsonObject)["local"].let { (it as JsonObject)["extra_args"] }
        assertEquals("[]", args.toString())
    }

    @Test
    fun `null 覆盖成 null 而不是删除键`() {
        val base = json("""{"app":{"profile":"work"}}""")
        val over = json("""{"app":{"profile":null}}""")
        val merged = deepMergeJson(base, over)
        val app = merged["app"] as JsonObject
        assertTrue(app.containsKey("profile"), "键必须还在（null 是有效值）")
        assertEquals("null", app["profile"].toString())
    }

    @Test
    fun `标量被覆盖`() {
        val merged = deepMergeJson(json("""{"app":{"log_level":"INFO"}}"""), json("""{"app":{"log_level":"DEBUG"}}"""))
        assertEquals("\"DEBUG\"", (merged["app"] as JsonObject)["log_level"].toString())
    }

    @Test
    fun `setJsonAt 建立中间层并按路径写入`() {
        val out = setJsonAt(JsonObject(emptyMap()), "backend.remote.base_url", JsonPrimitive("http://x"))
        assertEquals(
            """{"backend":{"remote":{"base_url":"http://x"}}}""",
            out.toString().replace("\\s".toRegex(), ""),
        )
    }

    @Test
    fun `flattenJsonKeys 展开到叶子`() {
        val keys = flattenJsonKeys(json("""{"a":{"b":1,"c":{"d":[1,2]}}}"""))
        assertEquals(setOf("a.b", "a.c.d"), keys)
    }
}

class DescriptorWalkTest {
    @Test
    fun `descriptorAt 能导航到三层字段且类型正确`() {
        val descriptor = descriptorAt(serializerDescriptorOfConfig(), "backend.remote.base_url")
        assertEquals(kotlinx.serialization.descriptors.PrimitiveKind.STRING, descriptor?.kind)
        val timeout = descriptorAt(serializerDescriptorOfConfig(), "backend.local.ready_timeout_ms")
        assertEquals(kotlinx.serialization.descriptors.PrimitiveKind.INT, timeout?.kind)
        val mode = descriptorAt(serializerDescriptorOfConfig(), "backend.mode")
        assertEquals(kotlinx.serialization.descriptors.SerialKind.ENUM, mode?.kind)
    }

    @Test
    fun `descriptorAt 对不存在的键返回 null`() {
        assertNull(descriptorAt(serializerDescriptorOfConfig(), "backend.remote.base_urll"))
        assertNull(descriptorAt(serializerDescriptorOfConfig(), "nope"))
    }

    @Test
    fun `knownConfigKeyPaths 覆盖所有分组且不含数组元素`() {
        val keys = knownConfigKeyPaths(serializerDescriptorOfConfig())
        listOf(
            "backend.mode",
            "backend.local.ready_timeout_ms",
            "backend.local.extra_args",
            "backend.remote.token.source",
            "app.log.keep_days",
            "app.window.min_width",
            "app.close_to_tray",
        ).forEach { assertTrue(it in keys, "缺少键 $it") }
        assertTrue(keys.none { it.contains("extra_args.") }, "数组元素不该出现在键路径里")
    }

    @Test
    fun `键路径到环境变量名是机械映射`() {
        assertEquals("HDC_BACKEND_REMOTE_BASE_URL", keyPathToEnvName("backend.remote.base_url"))
        assertEquals("hdc.app.log.keep_days", keyPathToPropertyName("app.log.keep_days"))
    }
}

class ConfigAssemblerTest {
    private fun assemble(
        base: JsonObject = JsonObject(emptyMap()),
        custom: JsonObject? = null,
        properties: Map<String, String> = emptyMap(),
        envs: Map<String, String> = emptyMap(),
        warnings: MutableList<String> = mutableListOf(),
    ): AppConfig = ConfigAssembler(
        source = MapConfigSource(properties, envs),
        warn = { warnings += it },
    ).assemble(base, custom)

    @Test
    fun `空文件全部取默认值`() {
        val config = assemble()
        assertEquals(BackendMode.REMOTE, config.backend.mode)
        assertEquals("http://127.0.0.1:9119", config.backend.remote.baseUrl)
        assertEquals(90_000, config.backend.local.readyTimeoutMs)
        assertEquals(1440, config.app.window.width)
        // 默认不提权：行为与加这个开关之前完全一致
        assertEquals(ElevateMode.NEVER, config.app.elevate)
    }

    @Test
    fun `custom 里把 elevate 改成 ask`() {
        val config = assemble(
            base = json("""{"app":{"elevate":"never"}}"""),
            custom = json("""{"app":{"elevate":"ask"}}"""),
        )
        assertEquals(ElevateMode.ASK, config.app.elevate)
    }

    @Test
    fun `autostart 默认 off，可改成 task`() {
        assertEquals(AutostartMode.OFF, assemble().app.autostart)
        val config = assemble(custom = json("""{"app":{"autostart":"task"}}"""))
        assertEquals(AutostartMode.TASK, config.app.autostart)
    }

    @Test
    fun `环境变量也能打开提权，非法值直接拒绝启动`() {
        val config = assemble(envs = mapOf("HDC_APP_ELEVATE" to "ask"))
        assertEquals(ElevateMode.ASK, config.app.elevate)

        // 环境变量是"部署面显式指定"，拼错必须当场失败而不是静默回落 ——
        // 静默回落会让"我明明设了却没生效"变成玄学（与配置文件里的未知键不同）。
        val failure = assertFailsWith<ConfigError> {
            assemble(envs = mapOf("HDC_APP_ELEVATE" to "yes"))
        }
        assertTrue(failure.message.orEmpty().contains("HDC_APP_ELEVATE"), failure.message.orEmpty())
    }

    @Test
    fun `custom 覆盖基础值且保留同组其它字段`() {
        val config = assemble(
            base = json("""{"backend":{"remote":{"base_url":"http://a","connect_timeout_ms":3000}}}"""),
            custom = json("""{"backend":{"remote":{"base_url":"http://b"}}}"""),
        )
        assertEquals("http://b", config.backend.remote.baseUrl)
        assertEquals(3000, config.backend.remote.connectTimeoutMs)
    }

    @Test
    fun `环境变量覆盖标量并按类型解析`() {
        val config = assemble(
            envs = mapOf(
                "HDC_APP_WINDOW_WIDTH" to "1600",
                "HDC_APP_WINDOW_HEIGHT" to "1000",
                "HDC_APP_LOG_LEVEL" to "debug",
                "HDC_BACKEND_REMOTE_BASE_URL" to "http://192.168.1.10:9119",
            ),
        )
        assertEquals(1600, config.app.window.width)
        assertEquals(1000, config.app.window.height)
        assertEquals("debug", config.app.logLevel)
        assertEquals("http://192.168.1.10:9119", config.backend.remote.baseUrl)
    }

    @Test
    fun `启动参数优先于环境变量`() {
        val config = assemble(
            properties = mapOf("hdc.app.window.width" to "1280"),
            envs = mapOf("HDC_APP_WINDOW_WIDTH" to "1600"),
        )
        assertEquals(1280, config.app.window.width)
    }

    @Test
    fun `数组字段接受 JSON 文本`() {
        val config = assemble(envs = mapOf("HDC_BACKEND_LOCAL_EXTRA_ARGS" to """["--isolated","--skip-build"]"""))
        assertEquals(listOf("--isolated", "--skip-build"), config.backend.local.extraArgs)
    }

    @Test
    fun `已废弃的单实例开关给出明确说法而不是未知键`() {
        val warnings = mutableListOf<String>()
        val config = assemble(custom = json("""{"app":{"single_instance":false}}"""), warnings = warnings)
        assertEquals("http://127.0.0.1:9119", config.backend.remote.baseUrl)
        assertTrue(warnings.any { it.contains("app.single_instance") && it.contains("已废弃") }, "应说明已废弃：$warnings")
        assertTrue(warnings.none { it.contains("未知配置键") && it.contains("single_instance") }, "不该当成未知键：$warnings")
    }

    @Test
    fun `未知键只告警不阻断`() {
        val warnings = mutableListOf<String>()
        val config = assemble(
            custom = json("""{"backend":{"remote":{"base_urll":"http://typo"}}}"""),
            warnings = warnings,
        )
        assertEquals("http://127.0.0.1:9119", config.backend.remote.baseUrl)
        assertTrue(warnings.any { it.contains("backend.remote.base_urll") }, "应告警具体键名：$warnings")
    }

    @Test
    fun `拼错的 HDC 变量只告警不阻断`() {
        val warnings = mutableListOf<String>()
        val config = assemble(
            envs = mapOf("HDC_APP_WINDOW_WIDHT" to "1600", "HDC_HOME" to "/tmp"),
            warnings = warnings,
        )
        assertEquals(1440, config.app.window.width)
        assertTrue(warnings.any { it.contains("HDC_APP_WINDOW_WIDHT") }, "应告警具体变量名：$warnings")
        assertTrue(warnings.none { it.contains("HDC_HOME") }, "引导变量不该被当成未知：$warnings")
    }

    @Test
    fun `非整数环境变量必须报错并指出变量名与原值`() {
        val failure = assertFailsWith<ConfigError> {
            assemble(envs = mapOf("HDC_APP_WINDOW_WIDTH" to "abc"))
        }
        val message = failure.message.orEmpty()
        assertTrue("HDC_APP_WINDOW_WIDTH" in message, "错误里要有变量名：$message")
        assertTrue("abc" in message, "错误里要有原值：$message")
    }

    @Test
    fun `非法枚举值报错并列出候选`() {
        val failure = assertFailsWith<ConfigError> {
            assemble(envs = mapOf("HDC_BACKEND_MODE" to "cloud"))
        }
        val message = failure.message.orEmpty()
        assertTrue("local" in message && "remote" in message, "要列出候选值：$message")
    }

    @Test
    fun `local 模式合法且空 hermes_dir 被拒`() {
        // 2026-10-28：local 已实现，配置层不再拦截；缺目录由启动页报具体原因。
        val config = assemble(envs = mapOf("HDC_BACKEND_MODE" to "local"))
        assertEquals(BackendMode.LOCAL, config.backend.mode)

        val failure = assertFailsWith<ConfigError> {
            assemble(
                envs = mapOf(
                    "HDC_BACKEND_MODE" to "local",
                    "HDC_BACKEND_LOCAL_HERMES_DIR" to "  ",
                ),
            )
        }
        assertTrue("hermes_dir" in failure.message.orEmpty(), "错误应指明字段：${failure.message}")
    }

    @Test
    fun `就绪超时低于下限被拒`() {
        val failure = assertFailsWith<ConfigError> {
            assemble(envs = mapOf("HDC_BACKEND_LOCAL_READY_TIMEOUT_MS" to "30000"))
        }
        assertTrue("45000" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `日志级别非法被拒且列出可选值`() {
        val failure = assertFailsWith<ConfigError> { assemble(envs = mapOf("HDC_APP_LOG_LEVEL" to "VERBOSE")) }
        assertTrue("INFO" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `base_url 必须是 http 或 https`() {
        val failure = assertFailsWith<ConfigError> {
            assemble(envs = mapOf("HDC_BACKEND_REMOTE_BASE_URL" to "127.0.0.1:9119"))
        }
        assertTrue("http" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `最小窗口大于初始窗口被拒`() {
        val failure = assertFailsWith<ConfigError> {
            assemble(envs = mapOf("HDC_APP_WINDOW_MIN_WIDTH" to "2000"))
        }
        assertTrue("最小尺寸" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `本地后端默认回环随机端口与随机凭据`() {
        val config = assemble(custom = json("""{"backend":{"mode":"local"}}"""))
        assertEquals("127.0.0.1", config.backend.local.host)
        assertEquals(0, config.backend.local.port)
        assertNull(config.backend.local.token, "默认不落盘凭据，每次启动随机生成")
    }

    @Test
    fun `监听地址 端口与固定凭据都可配置`() {
        val config = assemble(
            custom = json(
                """{"backend":{"mode":"local","local":{
                    "host":"0.0.0.0","port":19119,"token":"0123456789abcdef"}}}""",
            ),
        )
        assertEquals("0.0.0.0", config.backend.local.host)
        assertEquals(19119, config.backend.local.port)
        assertEquals("0123456789abcdef", config.backend.local.token)
    }

    @Test
    fun `端口越界与过短凭据被拒`() {
        val portFailure = assertFailsWith<ConfigError> {
            assemble(custom = json("""{"backend":{"mode":"local","local":{"port":70000}}}"""))
        }
        assertTrue("port" in portFailure.message.orEmpty(), portFailure.message.orEmpty())

        val tokenFailure = assertFailsWith<ConfigError> {
            assemble(custom = json("""{"backend":{"mode":"local","local":{"token":"short"}}}"""))
        }
        assertTrue("token" in tokenFailure.message.orEmpty(), tokenFailure.message.orEmpty())
    }
}

class AppOptionsTest {
    @Test
    fun `默认值符合 AGENTS 的超时约束`() {
        val options = parseAppOptions("{}")
        assertEquals(30_000L, options.network.requestTimeoutMs)
        assertEquals(45_000L, options.network.writeTimeoutMs)
        assertEquals(1_800_000L, options.network.promptAckTimeoutMs)
        assertEquals(ThemeMode.SYSTEM, options.ui.theme)
    }

    @Test
    fun `主题与超时可被覆盖`() {
        val options = parseAppOptions("""{"ui":{"theme":"dark"},"network":{"request_timeout_ms":12000}}""")
        assertEquals(ThemeMode.DARK, options.ui.theme)
        assertEquals(12_000L, options.network.requestTimeoutMs)
        assertEquals(45_000L, options.network.writeTimeoutMs, "未提到的字段保持默认")
    }

    @Test
    fun `acк 超时低于下限被拒`() {
        val failure = assertFailsWith<ConfigError> {
            parseAppOptions("""{"network":{"prompt_ack_timeout_ms":5000}}""")
        }
        assertTrue("60000" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `未知选项只告警`() {
        val warnings = mutableListOf<String>()
        val options = parseAppOptions("""{"ui":{"font_scale":1.5}}""", warnings::add)
        assertEquals(ThemeMode.SYSTEM, options.ui.theme)
        assertTrue(warnings.any { it.contains("ui.font_scale") }, "应告警具体键：$warnings")
    }

    @Test
    fun `holder 推送后 current 立即变化`() {
        AppOptionsHolder.reset()
        val before = dev.shinglem.hermes.core.transport.Timeouts.DEFAULT_MS
        AppOptionsHolder.set(AppOptions(network = NetworkOptions(requestTimeoutMs = 9_000)))
        assertEquals(9_000L, dev.shinglem.hermes.core.transport.Timeouts.DEFAULT_MS)
        AppOptionsHolder.reset()
        assertEquals(before, dev.shinglem.hermes.core.transport.Timeouts.DEFAULT_MS)
    }
}