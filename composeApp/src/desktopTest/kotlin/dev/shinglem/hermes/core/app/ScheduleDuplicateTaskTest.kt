package dev.shinglem.hermes.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "同一份安装只该有一条自启任务"的识别规则。
 *
 * 背景：早期版本用固定名 `HermesCompose` 建任务，而且不带 `--silent` —— 升级后新名字
 * （`HermesCompose-<hash>`）与它对不上，两条同时触发，旧那条每次都把主窗口弹出来。
 * 规则必须只碰**同一个 exe** 的任务，否则会删掉另一份正在用的安装。
 */
class ScheduleDuplicateTaskTest {

    private val current = "HermesCompose-aaaaaaaa"
    private val exe = "D:\\apps\\hermes-desktop-compose\\hermes-desktop-compose.exe"

    private fun task(name: String, command: String, arguments: String? = null) = buildString {
        append("<Task version=\"1.4\"><RegistrationInfo><URI>\\")
        append(name)
        append("</URI></RegistrationInfo><Actions Context=\"Author\"><Exec><Command>")
        append(command)
        append("</Command>")
        if (arguments != null) append("<Arguments>$arguments</Arguments>")
        append("</Exec></Actions></Task>")
    }

    @Test
    fun `早期固定名任务指向同一个 exe 时会被认定为多余`() {
        val xml = "<Tasks>" + task("HermesCompose", exe) + "</Tasks>"
        assertEquals(listOf("HermesCompose"), ScheduledAutostart.parseDuplicateTasks(xml, current, exe))
    }

    @Test
    fun `当前任务自己不会被当成多余`() {
        val xml = "<Tasks>" + task(current, exe, "--silent") + "</Tasks>"
        assertTrue(ScheduledAutostart.parseDuplicateTasks(xml, current, exe).isEmpty())
    }

    @Test
    fun `指向另一份安装的任务不碰`() {
        val xml = "<Tasks>" + task("HermesCompose-cafebabe", "D:\\Hermes\\hermes-desktop-compose.exe") + "</Tasks>"
        assertTrue(ScheduledAutostart.parseDuplicateTasks(xml, current, exe).isEmpty())
    }

    @Test
    fun `路径的大小写与斜杠写法不影响匹配`() {
        val xml = "<Tasks>" + task("HermesCompose", "d:/APPS/Hermes-Desktop-Compose/HERMES-DESKTOP-COMPOSE.EXE") + "</Tasks>"
        assertEquals(listOf("HermesCompose"), ScheduledAutostart.parseDuplicateTasks(xml, current, exe))
    }

    @Test
    fun `别的产品的任务一律忽略`() {
        val xml = "<Tasks>" +
            task("Hermes-Netcatty-Launch", exe) +
            task("HermesDashboard", exe) +
            task("HermesCompose-tooshort", exe) +
            "</Tasks>"
        assertTrue(ScheduledAutostart.parseDuplicateTasks(xml, current, exe).isEmpty())
    }

    @Test
    fun `没有任务时返回空`() {
        assertTrue(ScheduledAutostart.parseDuplicateTasks("<Tasks></Tasks>", current, exe).isEmpty())
    }

    @Test
    fun `参数里必须真的有 silent 才算合格`() {
        assertTrue(ScheduledAutostart.hasSilentArgument("--silent"))
        assertTrue(ScheduledAutostart.hasSilentArgument("  --SILENT  "))
        assertTrue(ScheduledAutostart.hasSilentArgument("--tray"))
        assertTrue(ScheduledAutostart.hasSilentArgument("--verbose --silent"))
        assertFalse(ScheduledAutostart.hasSilentArgument(null))
        assertFalse(ScheduledAutostart.hasSilentArgument(""))
        assertFalse(ScheduledAutostart.hasSilentArgument("--silent-profile"))
    }
}
