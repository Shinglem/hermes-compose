package dev.shinglem.hermes.core.prefs

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `JsonFileSettings` 的落盘行为。
 *
 * 这些是"看起来显然、实际最容易坏"的点：延迟落盘有没有真的发生、
 * 原子替换会不会留下半个文件、坏文件会不会让用户丢光偏好。
 */
class JsonFileSettingsTest {

    private fun newFile(): File =
        Files.createTempDirectory("hstate").resolve("state.json").toFile()

    @Test
    fun `flush 立即落盘且能重新读回`() {
        val file = newFile()
        val first = JsonFileSettings(file)
        first.putString(PrefKeys.UI_MODE, AppPreferences.MODE_CAPABILITIES)
        first.putString(PrefKeys.draftsPlaceholder(), "草稿")
        first.flush()
        first.close()

        assertTrue(file.isFile)
        val second = JsonFileSettings(file)
        assertEquals(AppPreferences.MODE_CAPABILITIES, second.getStringOrNull(PrefKeys.UI_MODE))
        assertEquals("草稿", second.getStringOrNull(PrefKeys.draftsPlaceholder()))
        second.close()
    }

    @Test
    fun `不 flush 时写入只在内存里`() {
        val file = newFile()
        val settings = JsonFileSettings(file, flushDelayMs = 60_000)
        settings.putString(PrefKeys.UI_MODE, "capabilities")

        assertEquals("capabilities", settings.getStringOrNull(PrefKeys.UI_MODE))
        assertFalse(file.exists())
        settings.close()
    }

    @Test
    fun `超出延迟后自动落盘`() {
        val file = newFile()
        val settings = JsonFileSettings(file, flushDelayMs = 55)
        settings.putString(PrefKeys.UI_MODE, "capabilities")

        val deadline = System.currentTimeMillis() + 5_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue(file.isFile, "延迟落盘没发生")
        settings.close()
    }

    @Test
    fun `刷新同一个键不会堆出一堆旧值`() {
        val file = newFile()
        val settings = JsonFileSettings(file, flushDelayMs = 60_000)
        repeat(5) { settings.putString(PrefKeys.UI_MODE, "capabilities") }
        settings.putString(PrefKeys.UI_MODE, "sessions")
        settings.flush()
        settings.close()

        val text = file.readText(Charsets.UTF_8)
        assertEquals(1, Regex("\"${PrefKeys.UI_MODE}\"").findAll(text).count())
        assertTrue(text.contains("\"sessions\""))
    }

    @Test
    fun `坏文件备份为 corrupt 并从空状态开始`() {
        val file = newFile()
        file.parentFile.mkdirs()
        file.writeText("{ this is broken", Charsets.UTF_8)

        val settings = JsonFileSettings(file)
        assertEquals(0, settings.size)
        assertTrue(File(file.parentFile, "${file.name}.corrupt").isFile)
        settings.close()
    }

    @Test
    fun `字符串不会被当成数字或布尔读出来`() {
        val file = newFile()
        val settings = JsonFileSettings(file, flushDelayMs = 60_000)
        settings.putString("k.num", "42")
        settings.putString("k.bool", "true")

        assertNull(settings.getIntOrNull("k.num"))
        assertNull(settings.getBooleanOrNull("k.bool"))
        assertEquals(42, settings.getInt("k.num", 42))
        settings.close()
    }

    @Test
    fun `remove 与 clear 会落盘`() {
        val file = newFile()
        val settings = JsonFileSettings(file)
        settings.putString("a", "1")
        settings.putString("b", "2")
        settings.remove("a")
        settings.flush()
        settings.close()

        val reopened = JsonFileSettings(file)
        assertFalse(reopened.hasKey("a"))
        assertTrue(reopened.hasKey("b"))

        reopened.clear()
        reopened.flush()
        reopened.close()

        val third = JsonFileSettings(file)
        assertEquals(0, third.size)
        third.close()
    }

    @Test
    fun `没有文件时不创建`() {
        val file = newFile()
        val settings = JsonFileSettings(file)
        assertEquals(0, settings.size)
        settings.close()
        assertFalse(file.exists())
    }
}

/** 草稿键名要带 session id，测试里给个固定值即可。 */
private fun PrefKeys.draftsPlaceholder(): String = draft("session-1")
