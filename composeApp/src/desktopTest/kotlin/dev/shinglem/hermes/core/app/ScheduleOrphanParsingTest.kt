package dev.shinglem.hermes.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 失效自启任务的识别规则。
 *
 * 这条逻辑决定"会不会去建议用户删掉别人的任务"，所以宁可保守：
 * 名字必须**完全**是我们那一套（`HermesCompose` / `HermesCompose-<8 hex>`），
 * 且指向的程序确实不存在。
 */
class ScheduleOrphanParsingTest {

    private val current = "HermesCompose-aaaaaaaa"

    private fun task(name: String, command: String) = """
        <Task version="1.4">
          <RegistrationInfo><URI>\$name</URI></RegistrationInfo>
          <Actions Context="Author"><Exec><Command>$command</Command><Arguments>--silent</Arguments></Exec></Actions>
        </Task>
    """.trimIndent()

    @Test
    fun `指向已不存在程序的本产品任务会被列出`() {
        val xml = "<Tasks>" + task("HermesCompose-deadbeef", "E:\\Old\\Hermes.exe") + "</Tasks>"
        val orphans = ScheduledAutostart.parseOrphans(xml, current) { false }

        assertEquals(1, orphans.size)
        assertEquals("HermesCompose-deadbeef", orphans[0].name)
        assertEquals("E:\\Old\\Hermes.exe", orphans[0].command)
    }

    @Test
    fun `当前正在用的任务不会被当成失效`() {
        val xml = "<Tasks>" + task(current, "D:\\Hermes\\Hermes.exe") + "</Tasks>"
        assertTrue(ScheduledAutostart.parseOrphans(xml, current) { false }.isEmpty())
    }

    @Test
    fun `指向仍存在的程序的任务不会被动，可能是另一份在用的安装`() {
        val xml = "<Tasks>" + task("HermesCompose-cafebabe", "C:\\Other\\Hermes.exe") + "</Tasks>"
        assertTrue(ScheduledAutostart.parseOrphans(xml, current) { it == "C:\\Other\\Hermes.exe" }.isEmpty())
    }

    @Test
    fun `名字前缀相同但不是本产品的任务一律忽略`() {
        val xml = "<Tasks>" +
            task("Hermes-Netcatty-Launch", "C:\\app\\Netcatty\\Netcatty.exe") +
            task("HermesCompose-tooshort", "C:\\gone\\Hermes.exe") +
            task("HermesCompose-zzzzzzzz", "C:\\gone\\Hermes.exe") +
            task("HermesDashboard", "wscript.exe") +
            "</Tasks>"
        assertTrue(
            ScheduledAutostart.parseOrphans(xml, current) { false }.isEmpty(),
            "只认 HermesCompose / HermesCompose-<8 hex>",
        )
    }

    @Test
    fun `早期版本固定名任务指向已不存在的程序时也会被列出`() {
        val xml = "<Tasks>" + task("HermesCompose", "E:\\Old\\hermes-desktop-compose.exe") + "</Tasks>"
        val orphans = ScheduledAutostart.parseOrphans(xml, current) { false }

        assertEquals(listOf("HermesCompose"), orphans.map { it.name })
    }

    @Test
    fun `多个失效任务都会被列出，且名字里的反斜杠被去掉`() {
        val xml = "<Tasks>" +
            task("HermesCompose-11111111", "D:\\a\\Hermes.exe") +
            task("HermesCompose-22222222", "D:\\b\\Hermes.exe") +
            "</Tasks>"
        val orphans = ScheduledAutostart.parseOrphans(xml, current) { false }
        assertEquals(listOf("HermesCompose-11111111", "HermesCompose-22222222"), orphans.map { it.name })
    }

    @Test
    fun `没有任务时返回空`() {
        assertTrue(ScheduledAutostart.parseOrphans("<Tasks></Tasks>", current) { false }.isEmpty())
    }
}
