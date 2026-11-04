package dev.shinglem.hermes.core.app

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「原样重启自己」依赖拿到完整命令行。`ProcessHandle.Info.arguments()` 在 Windows 上可能为空，
 * 所以必须有 Win32 回退 —— 这条测的就是回退真的能解析出参数。
 */
class WindowsCommandLineTest {

    @Test
    fun `win32 命令行能解析出 exe 与参数`() {
        val argv = WindowsCommandLine.current()
        assertTrue(argv != null, "拿不到命令行")
        assertTrue(argv.isNotEmpty(), "命令行是空的")
        assertTrue(argv.first().isNotBlank(), "第一个参数（exe 路径）为空：${argv}")
        assertTrue(argv.size > 1, "只有 exe、没有参数：${argv}")
        println("argv(${argv.size}) = ${argv.joinToString(" | ") { it.take(80) }}")
    }
}
