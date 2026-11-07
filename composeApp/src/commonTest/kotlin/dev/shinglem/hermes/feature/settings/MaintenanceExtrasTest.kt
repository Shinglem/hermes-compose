package dev.shinglem.hermes.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 维护页扩展的纯逻辑：debug-share 链接提取 + 作用域页判定。 */
class MaintenanceExtrasTest {

    @Test
    fun extractsHttpUrlsFromDebugSharePayload() {
        val payload = """
            {"ok":true,"report":"https://paste.example.com/abc123","logs":["https://paste.example.com/def456"]}
        """.trimIndent()
        assertEquals(
            listOf("https://paste.example.com/abc123", "https://paste.example.com/def456"),
            collectHttpUrls(payload),
        )
        // 重复链接只留一份；尾部标点不算链接的一部分
        assertEquals(
            listOf("https://x.test/a"),
            collectHttpUrls("https://x.test/a, https://x.test/a. (https://x.test/a)"),
        )
        assertEquals(emptyList(), collectHttpUrls("""{"ok":true}"""))
    }

    @Test
    fun profileScopedPagesAreTheOnesThatReadBackendData() {
        assertTrue(SettingsPage.Memory.isProfileScoped())
        assertTrue(SettingsPage.MainModel.isProfileScoped())
        assertTrue(SettingsPage.ProviderKeys.isProfileScoped())
        assertTrue(SettingsPage.Approvals.isProfileScoped())
        assertTrue(SettingsPage.Integrations.isProfileScoped())

        assertFalse(SettingsPage.Catalog(0).isProfileScoped())
        assertFalse(SettingsPage.Notifications.isProfileScoped())
        assertFalse(SettingsPage.About.isProfileScoped())
    }

    @Test
    fun maintenancePagesCarryTheirOwnScopeBar() {
        assertTrue(SettingsPage.Memory.hasOwnProfileScope())
        assertTrue(SettingsPage.Integrations.hasOwnProfileScope())
        assertFalse(SettingsPage.MainModel.hasOwnProfileScope())
        assertFalse(SettingsPage.ProviderKeys.hasOwnProfileScope())
    }
}
