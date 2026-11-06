package dev.shinglem.hermes.feature.settings

import dev.shinglem.hermes.core.app.AppRestarter
import dev.shinglem.hermes.core.app.RestartSupport
import dev.shinglem.hermes.core.config.AppConfig
import dev.shinglem.hermes.core.config.AppOptions
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.core.config.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsCatalogTest {

    @AfterTest
    fun resetOptions() = AppOptionsHolder.reset()

    @Test
    fun `catalog covers every config key we ship`() {
        val keys = SettingsCatalog.allFields.map { it.key }.toSet()
        listOf(
            "backend.mode",
            "backend.local.host",
            "backend.local.port",
            "backend.local.token",
            "backend.local.hermes_dir",
            "backend.local.home_dir",
            "backend.local.ready_timeout_ms",
            "backend.remote.base_url",
            "app.data_dir",
            "app.log_level",
            "app.log.keep_days",
            "app.window.width",
            "app.close_to_tray",
            "app.elevate",
            "app.autostart",
            "options.ui.theme",
            "options.network.prompt_ack_timeout_ms",
        ).forEach { assertTrue(it in keys, "缺少设置项：$it") }
    }

    @Test
    fun `snapshot reads config and options into text values`() {
        val snapshot = SettingsCatalog.snapshot(
            AppConfig(),
            AppOptions(),
        )
        // AppConfig() 的 Kotlin 默认是 remote；local 来自随包 config.json。
        assertEquals("remote", snapshot["backend.mode"])
        assertEquals("90000", snapshot["backend.local.ready_timeout_ms"])
        assertEquals("127.0.0.1", snapshot["backend.local.host"])
        assertEquals("0", snapshot["backend.local.port"])
        assertEquals("", snapshot["backend.local.token"], "null 凭据在表单里显示为空，而不是 \"null\"")
        assertEquals("true", snapshot["app.close_to_tray"])
        assertEquals("system", snapshot["options.ui.theme"])
        assertEquals("1800000", snapshot["options.network.prompt_ack_timeout_ms"])
    }

    @Test
    fun `options prefixed fields are live and write to the options overlay`() {
        val live = SettingsCatalog.allFields.filter { it.scope == SettingsScope.LIVE }.map { it.key }
        assertTrue(live.all { it.startsWith("options.") })
        assertEquals("ui.theme", SettingsField("options.ui.theme", "x", null, SettingsKind.ENUM).jsonPath)
        assertEquals(
            SettingsScope.RESTART,
            SettingsField("app.close_to_tray", "x", null, SettingsKind.BOOLEAN).scope,
        )
    }

    @Test
    fun `every catalog group is reachable from a section`() {
        val sections = SettingsCatalog.sections(debugAvailable = true)
        val referenced = sections
            .flatMap { it.pages }
            .mapNotNull { (it.second as? SettingsPage.Catalog)?.index }
            .toSet()
        assertEquals(
            SettingsCatalog.groups.indices.toSet(),
            referenced,
            "有字段组没挂到左栏（会变成不可达页面），或索引越界",
        )
    }

    @Test
    fun `no section is empty and no page is duplicated`() {
        val sections = SettingsCatalog.sections(debugAvailable = true)
        sections.forEach { assertTrue(it.pages.isNotEmpty(), "空分组：${it.title}") }
        val pages = sections.flatMap { it.pages }.map { it.second }
        assertEquals(pages.size, pages.toSet().size, "同一个页面被挂到多个分组：$pages")
        val keys = sections.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "分组 key 重复：$keys")
    }

    @Test
    fun `default page is the first page of the first section`() {
        val first = SettingsCatalog.sections(debugAvailable = false).first()
        assertEquals(first.pages.first().second, SettingsCatalog.defaultPage)
    }

    @Test
    fun `integration section only appears when the official desktop is available`() {
        val without = SettingsCatalog.sections(debugAvailable = false).map { it.key }
        val with = SettingsCatalog.sections(debugAvailable = true).map { it.key }
        assertTrue("integration" !in without)
        assertTrue("integration" in with)
        assertEquals(with.size, without.size + 1)
    }

    @Test
    fun `catalog groups keep the curated order`() {
        assertEquals(
            listOf("后端", "网络", "界面", "窗口", "常规", "启动与权限", "日志"),
            SettingsCatalog.groups.map { it.title },
        )
    }
}

class SettingsViewModelTest {

    private class RecordingStore : SettingsStore {
        var config: JsonObject? = null
        var options: JsonObject? = null

        override fun saveConfigPatch(patch: JsonObject) {
            config = patch
        }

        override fun saveOptionsPatch(patch: JsonObject) {
            options = patch
        }
    }

    private class FakeRestarter(
        override val support: RestartSupport = RestartSupport.SUPPORTED,
        private val started: Boolean = true,
    ) : AppRestarter {
        var calls = 0

        override suspend fun restart(): Boolean {
            calls += 1
            return started
        }
    }

    private fun viewModel(
        store: SettingsStore,
        restarter: AppRestarter = FakeRestarter(),
    ): SettingsViewModel = SettingsViewModel(
        store = store,
        config = AppConfig(),
        restarter = restarter,
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )

    @AfterTest
    fun resetOptions() = AppOptionsHolder.reset()

    @Test
    fun `save splits restart and live patches`() {
        val store = RecordingStore()
        val viewModel = viewModel(store)

        viewModel.edit("app.close_to_tray", "false")
        viewModel.edit("options.ui.theme", "dark")
        viewModel.save()

        val config = assertNotNull(store.config, "config 覆盖层没写")
        assertEquals(JsonPrimitive(false), (config["app"] as JsonObject)["close_to_tray"])

        val options = assertNotNull(store.options, "options 覆盖层没写")
        assertEquals(JsonPrimitive("dark"), (options["ui"] as JsonObject)["theme"])
        assertEquals(true, viewModel.state.value.saved?.restartRequired)
    }

    @Test
    fun `invalid number is rejected without writing`() {
        val store = RecordingStore()
        val viewModel = viewModel(store)

        viewModel.edit("backend.local.ready_timeout_ms", "abc")
        viewModel.save()

        assertEquals(null, store.config)
        assertNotNull(viewModel.state.value.error)
    }

    @Test
    fun `saving without edits writes nothing`() {
        val store = RecordingStore()
        val viewModel = viewModel(store)

        viewModel.save()

        assertEquals(null, store.config, "没改动不该生成 config 覆盖层")
        assertEquals(null, store.options, "没改动不该生成 options 覆盖层")
        assertEquals(false, viewModel.state.value.saved?.restartRequired)
        assertNotNull(viewModel.state.value.saved)
    }

    @Test
    fun `only the changed key reaches the overlay`() {
        val store = RecordingStore()
        val viewModel = viewModel(store)

        viewModel.edit("app.log_level", "DEBUG")
        viewModel.save()

        val app = assertNotNull(store.config)["app"] as JsonObject
        // 只写改过的那一个键：把全部默认值抄进覆盖层会让基础文件的升级再也影响不到用户。
        assertEquals(setOf("log_level"), app.keys)
    }

    @Test
    fun `nullable text writes json null instead of skipping`() {
        val store = RecordingStore()
        val viewModel = viewModel(store)

        viewModel.edit("app.data_dir", "")
        viewModel.save()

        val config = assertNotNull(store.config)
        assertEquals(JsonNull, (config["app"] as JsonObject)["data_dir"])
    }

    @Test
    fun `restart asks the restarter and then requests exit`() {
        val restarter = FakeRestarter()
        val viewModel = viewModel(RecordingStore(), restarter)

        viewModel.restart()

        assertEquals(1, restarter.calls)
        assertEquals(true, viewModel.state.value.exitRequested)
        assertEquals(false, viewModel.state.value.restarting)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun `restart failure keeps the window alive with a reason`() {
        val viewModel = viewModel(RecordingStore(), FakeRestarter(support = RestartSupport.DEV_MODE, started = false))

        viewModel.restart()

        assertEquals(false, viewModel.state.value.exitRequested)
        assertEquals(false, viewModel.state.value.restarting)
        assertNotNull(viewModel.state.value.error, "失败必须给原因，不能静默不动")
    }
}
