package dev.shinglem.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatUsageTest {
    @Test
    fun `ratio prefers context max`() {
        val usage = SessionUsage(contextUsed = 25, contextMax = 100, contextPercent = 90.0)
        assertEquals(0.25f, usage.ratio)
        assertEquals(UsageLevel.LOW, usage.level)
    }

    @Test
    fun `ratio falls back to percent when max is unknown`() {
        val usage = SessionUsage(contextUsed = 10, contextMax = 0, contextPercent = 80.0)
        assertEquals(0.8f, usage.ratio)
        assertEquals(UsageLevel.HIGH, usage.level)
    }

    @Test
    fun `hasData is false for an empty usage`() {
        assertTrue(!SessionUsage().hasData)
    }

    @Test
    fun `tokens are compacted`() {
        assertEquals("999", formatTokens(999))
        assertEquals("1.0k", formatTokens(1_000))
        assertEquals("1.5k", formatTokens(1_500))
        assertEquals("1.2M", formatTokens(1_234_567))
    }
}
