package dev.shinglem.hermes.core.config

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置覆盖层的落盘行为：**只写改过的键、合并而不是覆盖整份文件**。
 *
 * 这两条一旦破了，用户的基础配置升级就再也影响不到他 —— 症状很轻（"改了没反应"），
 * 原因却极难定位。
 */
class FileSettingsStoreTest {

    private fun newPaths(): ConfigPaths {
        val home = Files.createTempDirectory("hsettings").toFile()
        val configDir = File(home, "config").apply { mkdirs() }
        return ConfigPaths(home = home, configDir = configDir)
    }

    @Test
    fun `patch 只写改过的键并保留已有覆盖值`() {
        val paths = newPaths()
        val store = FileSettingsStore(paths)

        store.saveConfigPatch(setJsonAt(JsonObject(emptyMap()), "app.log_level", JsonPrimitive("DEBUG")))
        store.saveConfigPatch(setJsonAt(JsonObject(emptyMap()), "backend.mode", JsonPrimitive("remote")))

        val written = ConfigJson.parseToJsonElement(paths.customFile.readText(Charsets.UTF_8)).jsonObject
        assertEquals("DEBUG", written["app"]!!.jsonObject["log_level"]!!.jsonPrimitive.content)
        assertEquals("remote", written["backend"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        // 第二次保存没有把第一次的键冲掉，也没有凭空写入别的键。
        assertEquals(setOf("app", "backend"), written.keys)
    }

    @Test
    fun `空 patch 不创建文件`() {
        val paths = newPaths()
        FileSettingsStore(paths).saveConfigPatch(JsonObject(emptyMap()))
        assertFalse(paths.customFile.exists())
    }

    @Test
    fun `options 覆盖层是独立文件`() {
        val paths = newPaths()
        val store = FileSettingsStore(paths)

        store.saveOptionsPatch(setJsonAt(JsonObject(emptyMap()), "ui.theme", JsonPrimitive("dark")))

        assertFalse(paths.customFile.exists(), "options 的改动不该写进 config 覆盖层")
        val written = ConfigJson.parseToJsonElement(paths.optionsCustomFile.readText(Charsets.UTF_8)).jsonObject
        assertEquals("dark", written["ui"]!!.jsonObject["theme"]!!.jsonPrimitive.content)
    }

    @Test
    fun `坏覆盖层按空对象处理，不让保存失败`() {
        val paths = newPaths()
        paths.customFile.writeText("{ broken", Charsets.UTF_8)
        val warnings = mutableListOf<String>()

        FileSettingsStore(paths) { warnings += it }
            .saveConfigPatch(setJsonAt(JsonObject(emptyMap()), "app.profile", JsonPrimitive("butler")))

        val written = ConfigJson.parseToJsonElement(paths.customFile.readText(Charsets.UTF_8)).jsonObject
        assertEquals("butler", written["app"]!!.jsonObject["profile"]!!.jsonPrimitive.content)
        assertTrue(warnings.any { it.contains("顶层不是 JSON 对象") }, "坏文件应被记录：$warnings")
    }

    @Test
    fun `深合并保留用户手写但设置页没碰的键`() {
        val paths = newPaths()
        paths.customFile.writeText(
            """{"app":{"window":{"width":1200}},"backend":{"remote":{"base_url":"http://127.0.0.1:9119"}}}""",
            Charsets.UTF_8,
        )

        FileSettingsStore(paths)
            .saveConfigPatch(setJsonAt(JsonObject(emptyMap()), "app.close_to_tray", JsonPrimitive(false)))

        val written = ConfigJson.parseToJsonElement(paths.customFile.readText(Charsets.UTF_8)).jsonObject
        assertEquals(1200, written["app"]!!.jsonObject["window"]!!.jsonObject["width"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, written["app"]!!.jsonObject["close_to_tray"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "http://127.0.0.1:9119",
            written["backend"]!!.jsonObject["remote"]!!.jsonObject["base_url"]!!.jsonPrimitive.content,
        )
    }
}
