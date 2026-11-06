package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.AppSection
import dev.shinglem.hermes.core.config.BackendSection
import dev.shinglem.hermes.core.config.ConfigPaths
import dev.shinglem.hermes.core.config.DebugSection
import dev.shinglem.hermes.core.config.LocalSection
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 官方桌面端的查找顺序（payload 自动探测 / config 覆盖 / 都没有 → 不出现入口）。 */
class OfficialDesktopDetectTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "hermes-official-desktop-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `payload 下的 official-desktop 会被自动探测到`() {
        val exe = File(root, "hermes${File.separator}official-desktop${File.separator}Hermes.exe")
        exe.parentFile.mkdirs()
        exe.writeText("stub")

        val launcher = OfficialDesktopLauncherImpl.detect(config(), paths())

        assertNotNull(launcher)
        assertEquals(exe.absolutePath, launcher.path)
    }

    @Test
    fun `config 里的绝对路径优先于自动探测`() {
        val auto = File(root, "hermes${File.separator}official-desktop${File.separator}Hermes.exe")
        auto.parentFile.mkdirs()
        auto.writeText("stub")
        val explicit = File(root, "elsewhere${File.separator}Hermes.exe")
        explicit.parentFile.mkdirs()
        explicit.writeText("stub")

        val launcher = OfficialDesktopLauncherImpl.detect(
            config(officialDesktop = explicit.absolutePath),
            paths(),
        )

        assertEquals(explicit.absolutePath, launcher?.path)
    }

    @Test
    fun `都没有时返回 null，设置页不出现调试入口`() {
        assertNull(OfficialDesktopLauncherImpl.detect(config(), paths()))
    }

    @Test
    fun `数据根与官方客户端默认位置分开，避免单实例锁互斥`() {
        val exe = File(root, "hermes${File.separator}official-desktop${File.separator}Hermes.exe")
        exe.parentFile.mkdirs()
        exe.writeText("stub")

        val launcher = OfficialDesktopLauncherImpl.detect(config(), paths())

        assertEquals(officialDesktopDataRoot(root).absolutePath, launcher?.dataDir)
    }

    @Test
    fun `启动环境变量指向独立 userData 与 HERMES_HOME`() {
        val dataRoot = File(root, "data${File.separator}official-desktop")
        val env = officialDesktopEnv(dataRoot)

        assertEquals(File(dataRoot, "userdata").absolutePath, env["HERMES_DESKTOP_USER_DATA_DIR"])
        assertEquals(File(dataRoot, "hermes-home").absolutePath, env["HERMES_HOME"])
    }

    @Test
    fun `给了后端地址与会话令牌时注入 REMOTE_URL 与 REMOTE_TOKEN`() {
        val dataRoot = File(root, "data${File.separator}official-desktop")
        val env = officialDesktopEnv(dataRoot, "http://127.0.0.1:9119", "tok-abc")

        // 两个必须成对出现：官方桌面只看到 URL 会直接抛错
        assertEquals("http://127.0.0.1:9119", env["HERMES_DESKTOP_REMOTE_URL"])
        assertEquals("tok-abc", env["HERMES_DESKTOP_REMOTE_TOKEN"])
    }

    @Test
    fun `缺令牌或后端未就绪时不注入 REMOTE_ 两个变量`() {
        val dataRoot = File(root, "data${File.separator}official-desktop")

        // 只有 URL 没有 token
        officialDesktopEnv(dataRoot, "http://127.0.0.1:9119", null).let { env ->
            assertNull(env["HERMES_DESKTOP_REMOTE_URL"], "只有 URL 时不能注入")
            assertNull(env["HERMES_DESKTOP_REMOTE_TOKEN"])
        }
        // 只有 token 没有 URL
        officialDesktopEnv(dataRoot, null, "tok").let { env ->
            assertNull(env["HERMES_DESKTOP_REMOTE_URL"])
            assertNull(env["HERMES_DESKTOP_REMOTE_TOKEN"])
        }
        // 后端未就绪时的 `:0` 占位地址
        officialDesktopEnv(dataRoot, "http://127.0.0.1:0", "tok").let { env ->
            assertNull(env["HERMES_DESKTOP_REMOTE_URL"], "占位端口不能注入")
            assertNull(env["HERMES_DESKTOP_REMOTE_TOKEN"])
        }
        // 隔离变量始终在
        assertEquals(File(dataRoot, "userdata").absolutePath, officialDesktopEnv(dataRoot)["HERMES_DESKTOP_USER_DATA_DIR"])
    }

    @Test
    fun `地址可用性判定`() {
        assertEquals(true, isUsableBackendUrl("http://127.0.0.1:9119"))
        assertEquals(true, isUsableBackendUrl("https://hermes.example.com:443/api"))
        assertEquals(false, isUsableBackendUrl("http://127.0.0.1:0"), "未就绪的占位地址")
        assertEquals(false, isUsableBackendUrl("http://127.0.0.1"), "缺端口")
        assertEquals(false, isUsableBackendUrl(""), "空串")
        assertEquals(false, isUsableBackendUrl("127.0.0.1:9119"), "缺 scheme")
    }

    @Test
    fun `sharedBackend 三态`() {
        assertTrue(launcher(url = { "http://127.0.0.1:0" }, token = { "tok" }).sharedBackend is SharedBackendState.Unavailable)
        assertTrue(launcher(url = { "http://127.0.0.1:57586" }, token = { null }).sharedBackend is SharedBackendState.Unavailable)
        assertEquals(
            SharedBackendState.Ready("http://127.0.0.1:57586"),
            launcher(url = { "http://127.0.0.1:57586" }, token = { "tok" }).sharedBackend,
        )
    }

    @Test
    fun `后端不可用时拒绝启动且不创建任何进程`() {
        var started = 0
        val launcher = launcher(
            url = { "http://127.0.0.1:0" },
            token = { "tok" },
            starter = { _, _, _ -> started++ },
        )

        val result = launcher.launch()

        assertIs<LaunchResult.Refused>(result)
        assertEquals(0, started, "后端没就绪时绝不能起进程 —— 那会另起一套后端")
    }

    @Test
    fun `探活失败时拒绝启动`() {
        var started = 0
        val launcher = launcher(
            url = { "http://127.0.0.1:57586" },
            token = { "tok" },
            probe = { _, _ -> false },
            starter = { _, _, _ -> started++ },
        )

        assertIs<LaunchResult.Refused>(launcher.launch())
        assertEquals(0, started)
    }

    @Test
    fun `就绪时带 REMOTE_URL 与 REMOTE_TOKEN 启动`() {
        var captured: Map<String, String>? = null
        val launcher = launcher(
            url = { "http://127.0.0.1:57586" },
            token = { "tok-abc" },
            probe = { _, _ -> true },
            starter = { _, _, env -> captured = env },
        )

        val result = launcher.launch()

        assertEquals(LaunchResult.Started("http://127.0.0.1:57586"), result)
        assertEquals("http://127.0.0.1:57586", captured?.get("HERMES_DESKTOP_REMOTE_URL"))
        assertEquals("tok-abc", captured?.get("HERMES_DESKTOP_REMOTE_TOKEN"))
    }

    private fun launcher(
        url: () -> String?,
        token: () -> String?,
        probe: (String, Long) -> Boolean = { _, _ -> true },
        starter: (File, File, Map<String, String>) -> Unit = { _, _, _ -> },
    ): OfficialDesktopLauncherImpl {
        val exe = File(root, "hermes${File.separator}official-desktop${File.separator}Hermes.exe")
        exe.parentFile.mkdirs()
        if (!exe.isFile) exe.writeText("stub")
        return OfficialDesktopLauncherImpl(
            exe = exe,
            dataRoot = File(root, "data${File.separator}official-desktop"),
            backendUrl = url,
            sessionToken = token,
            probe = probe,
            starter = starter,
        )
    }

    private fun config(officialDesktop: String? = null) = AppConfig(
        backend = BackendSection(local = LocalSection(hermesDir = "hermes")),
        app = AppSection(debug = DebugSection(officialDesktop = officialDesktop)),
    )

    private fun paths() = ConfigPaths(home = root, configDir = File(root, "config"))
}
