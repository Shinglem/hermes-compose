package dev.shinglem.hermes.core.backend

import dev.shinglem.hermes.core.config.LocalSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalBackendPlannerTest {

    private val payload = "S:/app/release/hermes"
    private val home = "S:/app/release/hermes-home"
    private val data = "C:/Users/u/AppData/Local/hermes-compose"
    private val local = LocalSection()

    private fun spec(overrides: LocalSection = local) = LocalBackendPlanner.backendSpec(
        payloadDir = payload,
        homeDir = home,
        dataDir = data,
        token = "tok",
        nonce = "abc",
        parentPid = 4242,
        parentStartMarker = "winms:99",
        local = overrides,
        osEnv = mapOf("PATH" to "C:/Windows", "SECRET_KEY" to "leak", "HDC_HOME" to "S:/app"),
    )

    @Test
    fun `backend is a single dashboard process on an ephemeral loopback port`() {
        val spec = spec()
        assertEquals("S:/app/release/hermes/bin/hermes.exe", spec.exe)
        // dashboard 同时提供 WS JSON-RPC / REST / Web UI，所以不再有独立的 serve 进程。
        assertEquals(
            listOf("dashboard", "--host", "127.0.0.1", "--port", "0", "--skip-build", "--no-open", "--isolated"),
            spec.args,
        )
        assertEquals("S:/app/release/hermes", spec.workDir)
        assertTrue(spec.readyFile!!.endsWith("ready-abc.json"))
    }

    @Test
    fun `configured host and port reach the backend args`() {
        val spec = spec(LocalSection(host = "0.0.0.0", port = 19119))
        assertEquals(
            listOf("dashboard", "--host", "0.0.0.0", "--port", "19119", "--skip-build", "--no-open", "--isolated"),
            spec.args,
        )
    }

    @Test
    fun `out of range port falls back to ephemeral`() {
        val spec = spec(LocalSection(port = 70_000))
        assertEquals("0", spec.args[spec.args.indexOf("--port") + 1])
    }

    @Test
    fun `wildcard binds connect through loopback`() {
        assertEquals("127.0.0.1", localConnectHost("0.0.0.0"))
        assertEquals("127.0.0.1", localConnectHost("::"))
        assertEquals("127.0.0.1", localConnectHost(""))
        assertEquals("127.0.0.1", localConnectHost(" 127.0.0.1 "))
        assertEquals("192.168.1.10", localConnectHost("192.168.1.10"))
    }

    @Test
    fun `web dist presence is detected from the payload layout`() {
        assertTrue(LocalBackendPlanner.hasWebDist(payload) { it.endsWith("web_dist/index.html") })
        assertFalse(LocalBackendPlanner.hasWebDist(payload) { false })
    }

    @Test
    fun `env is a whitelist - parent secrets never leak`() {
        val env = spec().env
        assertEquals(home, env["HERMES_HOME"])
        assertEquals("tok", env["HERMES_DASHBOARD_SESSION_TOKEN"])
        assertEquals("1", env["HERMES_DESKTOP"])
        assertEquals("4242", env["HERMES_PARENT_PID"])
        assertEquals("1", env["PYTHONUTF8"])
        assertEquals("", env["PYTHONPATH"])
        assertEquals("C:/Windows", env["PATH"])
        assertFalse(env.containsKey("SECRET_KEY"))
        assertFalse(env.containsKey("HDC_HOME"))
        assertFalse(env.containsKey("HERMES_WEB_DIST"))
    }

    @Test
    fun `run_cron off omits HERMES_DESKTOP`() {
        val env = spec(LocalSection(runCron = false)).env
        assertFalse(env.containsKey("HERMES_DESKTOP"))
    }

    @Test
    fun `ready timeout never goes below the 45s floor`() {
        assertEquals(45_000L, spec(LocalSection(readyTimeoutMs = 1_000)).timeoutMs)
        assertEquals(90_000L, spec().timeoutMs)
    }

    @Test
    fun `home classification rejects foreign payload and relative homes`() {
        val foreign = LocalBackendPlanner.foreignHomes(
            userHome = "C:/Users/u",
            appData = "C:/Users/u/AppData/Roaming",
            localAppData = "C:/Users/u/AppData/Local",
        )
        assertEquals(HomeVerdict.OK, LocalBackendPlanner.classifyHome(home, payload, foreign))
        assertEquals(
            HomeVerdict.IS_FOREIGN_HOME,
            LocalBackendPlanner.classifyHome("C:/Users/u/.hermes", payload, foreign),
        )
        assertEquals(
            HomeVerdict.RELATIVE,
            LocalBackendPlanner.classifyHome("hermes-home", payload, foreign),
        )
        assertEquals(
            HomeVerdict.INSIDE_PAYLOAD,
            LocalBackendPlanner.classifyHome("$payload/home", payload, foreign),
        )
    }

    @Test
    fun `sealed payload requires manifest and exe`() {
        assertTrue(LocalBackendPlanner.isSealedPayload(payload) { true })
        assertFalse(
            LocalBackendPlanner.isSealedPayload(payload) { it.endsWith("manifest.json") },
        )
    }

    @Test
    fun `ready sentinels parse anywhere in the stream`() {
        // uvicorn 的 stderr 块末尾没有换行，哨兵会被拼到上一行后面。
        assertEquals(
            9120,
            ReadySentinels.readyPort("Started server process [4711]HERMES_BACKEND_READY port=9120"),
        )
        assertEquals(9121, ReadySentinels.readyPort("HERMES_DASHBOARD_READY port=9121"))
        assertEquals(9119, ReadySentinels.portInUse("BACKEND_PORT_IN_USE port=9119"))
        assertEquals(9300, ReadySentinels.readyFilePort("""{"port":9300}"""))
        assertNull(ReadySentinels.readyPort("HERMES_BACKEND_READY"))
        // 前缀相同但不是哨兵的情况不能被误判
        assertNull(ReadySentinels.readyPort("XHERMES_BACKEND_READY port=1"))
    }
}
