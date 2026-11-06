package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayAttachPlannerTest {

    private val osEnv = mapOf(
        "PATH" to """C:\Windows""",
        "APPDATA" to """C:\Users\u\AppData\Roaming""",
        "HDC_HOME" to "should-not-leak",
        "HTTPS_PROXY" to "http://proxy:7890",
    )

    private val local = LocalSection(runCron = true, extraArgs = listOf("--isolated"))

    private fun ensure(
        local: LocalSection = this.local,
        browserHeaded: Boolean = false,
        browserArgs: String = "",
        elevated: Boolean = false,
    ) = GatewayAttachPlanner.ensureSpec(
        payloadDir = """S:\app\hermes""",
        homeDir = """S:\app\data\agent""",
        token = "tok-123",
        local = local,
        osEnv = osEnv,
        browserHeaded = browserHeaded,
        browserArgs = browserArgs,
        elevated = elevated,
    )

    @Test
    fun `ensure 用 gateway ensure --json`() {
        val spec = ensure()
        assertEquals("S:/app/hermes/bin/hermes.exe", spec.exe)
        assertEquals("gateway", spec.args[0])
        assertEquals("ensure", spec.args[1])
        assertTrue(spec.args.contains("--json"))
        assertEquals("--timeout", spec.args[3])
    }

    @Test
    fun `ensure 不设父进程看门狗与 ready 文件`() {
        val spec = ensure()
        assertNull(spec.readyFile)
        assertFalse(spec.env.containsKey("HERMES_PARENT_PID"))
        assertFalse(spec.env.containsKey("HERMES_PARENT_START_MARKER"))
        assertFalse(spec.env.containsKey("HERMES_DESKTOP_READY_FILE"))
    }

    @Test
    fun `ensure 强制注入 home 与 token`() {
        val spec = ensure()
        assertEquals("""S:\app\data\agent""", spec.env["HERMES_HOME"])
        assertEquals("tok-123", spec.env["HERMES_DASHBOARD_SESSION_TOKEN"])
        assertEquals("1", spec.env["HERMES_DESKTOP"])
        assertEquals("1", spec.env["HERMES_DISABLE_LAZY_INSTALLS"])
    }

    @Test
    fun `白名单之外的环境变量不继承`() {
        val spec = ensure()
        assertEquals("""C:\Windows""", spec.env["PATH"])
        assertFalse(spec.env.containsKey("HDC_HOME"))
        assertFalse(spec.env.containsKey("HTTPS_PROXY"))
    }

    @Test
    fun `不把 dashboard 的 extraArgs 拼进 ensure`() {
        val spec = ensure()
        assertFalse(spec.args.contains("--isolated"))
    }

    @Test
    fun `runCron 关闭时不给 daemon 设 HERMES_DESKTOP`() {
        val spec = ensure(local = LocalSection(runCron = false))
        assertFalse(spec.env.containsKey("HERMES_DESKTOP"))
    }

    @Test
    fun `浏览器开关与提权绕过参数透传`() {
        val spec = ensure(browserHeaded = true, browserArgs = "--remote-debugging-port=9222", elevated = true)
        assertEquals("1", spec.env["AGENT_BROWSER_HEADED"])
        val args = spec.env["AGENT_BROWSER_ARGS"].orEmpty()
        assertTrue(args.contains("--no-sandbox"))
        assertTrue(args.contains("--remote-debugging-port=9222"))
    }

    @Test
    fun `ready_timeout 有下限且客户端多等一段`() {
        val spec = ensure(local = LocalSection(readyTimeoutMs = 1_000))
        assertEquals(
            GatewayAttachPlanner.MIN_TIMEOUT_MS + GatewayAttachPlanner.CLIENT_GRACE_MS,
            spec.timeoutMs,
        )
        // 上游 --timeout 是秒，且要比我们自己的短，避免两边同时超时
        val seconds = spec.args[4].toInt()
        assertEquals(GatewayAttachPlanner.MIN_TIMEOUT_MS / 1000 - 1, seconds.toLong())
    }

    @Test
    fun `stop 只停本 home 且绝不带 --all`() {
        val spec = GatewayAttachPlanner.stopSpec(
            payloadDir = """S:\app\hermes""",
            homeDir = """S:\app\data\agent""",
            token = "tok-123",
            local = local,
            osEnv = osEnv,
        )
        assertEquals(listOf("gateway", "stop", "-y"), spec.args)
        assertFalse(spec.args.contains("--all"))
        assertEquals("""S:\app\data\agent""", spec.env["HERMES_HOME"])
        assertEquals(GatewayAttachPlanner.STOP_TIMEOUT_MS, spec.timeoutMs)
    }

    @Test
    fun `env 文件里的会话令牌会被解析出来`() {
        val text = """
            # comment
            SOME_KEY=1
            export HERMES_DASHBOARD_SESSION_TOKEN=abc123
            OTHER=2
        """.trimIndent()
        assertEquals("abc123", GatewayAttachPlanner.envFileToken(text))
    }

    @Test
    fun `env 文件里的令牌支持引号与等号`() {
        assertEquals(
            "t-1=2",
            GatewayAttachPlanner.envFileToken("""HERMES_DASHBOARD_SESSION_TOKEN="t-1=2""""),
        )
        assertEquals(
            "tok",
            GatewayAttachPlanner.envFileToken("HERMES_DASHBOARD_SESSION_TOKEN='tok'"),
        )
    }

    @Test
    fun `没有该键或值为空时返回 null`() {
        assertNull(GatewayAttachPlanner.envFileToken(null))
        assertNull(GatewayAttachPlanner.envFileToken(""))
        assertNull(GatewayAttachPlanner.envFileToken("OTHER=1"))
        assertNull(GatewayAttachPlanner.envFileToken("HERMES_DASHBOARD_SESSION_TOKEN="))
        assertNull(GatewayAttachPlanner.envFileToken("# HERMES_DASHBOARD_SESSION_TOKEN=abc"))
    }
}