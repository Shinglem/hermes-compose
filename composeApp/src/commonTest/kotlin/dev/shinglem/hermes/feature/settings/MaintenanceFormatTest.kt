package dev.shinglem.hermes.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.shinglem.hermes.core.contract.MemoryExternalDependencyDto
import dev.shinglem.hermes.core.contract.MemoryProviderSetupDto
import dev.shinglem.hermes.core.contract.MemoryProviderStatusDto

/**
 * 维护页的展示格式：这些数字直接进 UI，错了会让人误判"占用很小/很大"，
 * 所以按边界值钉一下。
 */
class MaintenanceFormatTest {

    @Test
    fun bytesSwitchUnitsAt1024() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("3.0 MB", formatBytes(1024L * 1024 * 3))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun countsUseThousandSeparators() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,000", formatCount(1000))
        assertEquals("1,234,567", formatCount(1_234_567))
    }

    @Test
    fun costKeepsTwoDecimals() {
        assertEquals("\$0.0", formatCost(0.0))
        assertEquals("\$1.23", formatCost(1.234))
        assertEquals("\$10.0", formatCost(10.0))
    }

    @Test
    fun memoryStatusLabelsAreChinese() {
        assertEquals("就绪", memoryStatusLabel("ready"))
        assertEquals("待配置", memoryStatusLabel("needs_config"))
        assertEquals("缺依赖", memoryStatusLabel("unavailable"))
        assertEquals("未找到", memoryStatusLabel("missing"))
        assertEquals("未知", memoryStatusLabel(""))
    }

    @Test
    fun hintsExplainMissingDependencyAndConfig() {
        val needsCommand = MemoryProviderStatusDto(
            name = "byterover",
            status = "unavailable",
            setup = MemoryProviderSetupDto(
                externalDependencies = listOf(
                    MemoryExternalDependencyDto(name = "brv", install = "curl -fsSL x | sh", check = "brv --version"),
                ),
                dependenciesInstalled = false,
            ),
        )
        val hints = memoryProviderHints(needsCommand)
        assertEquals(2, hints.size)
        assertTrue(hints[0].contains("缺少命令 brv"))
        assertTrue(hints[0].contains("curl -fsSL x | sh"))
        assertTrue(hints[1].contains("brv --version"))

        val needsEnv = MemoryProviderStatusDto(
            name = "retaindb",
            status = "needs_config",
            setup = MemoryProviderSetupDto(requiredEnv = listOf("RETAINDB_API_KEY")),
        )
        val envHints = memoryProviderHints(needsEnv)
        assertEquals(1, envHints.size)
        assertTrue(envHints[0].contains("RETAINDB_API_KEY"))
        assertTrue(envHints[0].contains(".env"))

        assertEquals(emptyList(), memoryProviderHints(MemoryProviderStatusDto(name = "x", status = "ready")))
        assertTrue(memoryProviderHints(MemoryProviderStatusDto(name = "x", status = "missing")).single().contains("不存在"))
    }
}
