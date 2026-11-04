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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    private fun config(officialDesktop: String? = null) = AppConfig(
        backend = BackendSection(local = LocalSection(hermesDir = "hermes")),
        app = AppSection(debug = DebugSection(officialDesktop = officialDesktop)),
    )

    private fun paths() = ConfigPaths(home = root, configDir = File(root, "config"))
}
