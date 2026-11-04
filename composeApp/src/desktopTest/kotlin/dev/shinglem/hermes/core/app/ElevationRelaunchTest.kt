package dev.shinglem.hermes.core.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ShellExecuteExW` 的结构与调用约定。
 *
 * 真正的 `runas` 必须人工点 UAC（提示在安全桌面上，脚本注入无效），所以这条用 `open`
 * 走同一条代码路径：偏移写错、UTF-16 漏了、返回值当反了，都会在这里立刻暴露。
 */
class ElevationRelaunchTest {

    @Test
    fun `open 能启动一个真实进程，说明结构偏移与调用约定正确`() {
        val cmd = File(System.getenv("COMSPEC") ?: "C:\\Windows\\System32\\cmd.exe")
        assertTrue(cmd.isFile, "找不到 cmd.exe：$cmd")

        assertEquals(
            ElevationRelaunch.Outcome.STARTED,
            ElevationRelaunch.launchForTest(cmd.absolutePath, "/c exit 0"),
        )
    }

    @Test
    fun `参数引号规则与 CommandLineToArgvW 互逆`() {
        // 便携包放在 D:\My Apps\Hermes 时，-cp 的值必然含空格；规则写错就启动失败。
        assertEquals("abc", ElevationRelaunch.quoteArgument("abc"))
        assertEquals("\"a b\"", ElevationRelaunch.quoteArgument("a b"))
        assertEquals("\"\"", ElevationRelaunch.quoteArgument(""))
        assertEquals("\"a\\\"b\"", ElevationRelaunch.quoteArgument("a\"b"))
        assertEquals("a\\", ElevationRelaunch.quoteArgument("a\\"))
        // 引号内的结尾反斜杠要翻倍，否则会把右引号转义掉
        assertEquals("\"a b\\\\\"", ElevationRelaunch.quoteArgument("a b\\"))
    }

    @Test
    fun `不存在的可执行文件返回 FAILED 而不是抛异常`() {
        assertEquals(
            ElevationRelaunch.Outcome.FAILED,
            ElevationRelaunch.launchForTest("C:\\definitely\\missing\\hdc-not-here.exe", ""),
        )
    }
}
