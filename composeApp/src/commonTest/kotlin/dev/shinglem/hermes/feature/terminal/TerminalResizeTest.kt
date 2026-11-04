package dev.shinglem.hermes.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** `terminal.resize` 的上报节奏：按会话去重，同会话同列数只报一次。 */
class TerminalResizeTest {

    private val sent = mutableListOf<Pair<String, Int>>()
    private val reporter = ColsReporter { sid, cols -> sent += sid to cols }

    @Test
    fun `没有会话时不报`() {
        reporter.onColumns(120)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `同一会话同列数只报一次，列数变了再报`() {
        reporter.onSessionChanged("s1")
        reporter.onColumns(120)
        reporter.onColumns(120)
        reporter.onColumns(100)
        assertEquals(listOf("s1" to 120, "s1" to 100), sent)
    }

    @Test
    fun `换会话后同样的列数要重新报`() {
        reporter.onSessionChanged("s1")
        reporter.onColumns(120)
        reporter.onSessionChanged("s2")
        reporter.onColumns(120)
        assertEquals(listOf("s1" to 120, "s2" to 120), sent)
    }

    @Test
    fun `非法列数忽略`() {
        reporter.onSessionChanged("s1")
        reporter.onColumns(0)
        reporter.onColumns(-5)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `切到同一会话不清去重`() {
        reporter.onSessionChanged("s1")
        reporter.onColumns(80)
        reporter.onSessionChanged("s1")
        reporter.onColumns(80)
        assertEquals(1, sent.size)
    }

    @Test
    fun `没装上发送端时不崩`() {
        ColsReporter().apply {
            onSessionChanged("s1")
            onColumns(100)
        }
    }
}
