package dev.shinglem.hermes.platform

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 补写 AUMID 的 PowerShell 载荷（**不执行** PowerShell）。
 *
 * 只测"拼出来的东西是否是这个脚本 + 是否按 UTF-16LE Base64 编码"——这两点错了会在真机上
 * 表现成"进程起来了但什么也没做"，很难从日志看出来。真正改快捷方式的行为留给集成验证。
 */
class ShortcutAumidWriterTest {

    private val lnk = File("C:\\Users\\某人\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Hermes.lnk")
    private val aumid = "Shinglem.HermesDesktop.fb97bfde"

    @Test
    fun `脚本包含 COM 补写的完整链条`() {
        val script = ShortcutAumidWriter.buildScript(lnk.absolutePath, aumid)
        // 只 Commit 不 Save 是不落盘的（实测坑 1）。
        assertTrue(script.contains("Add-Type -TypeDefinition"), script)
        assertTrue(script.contains("IPropertyStore"), script)
        assertTrue(script.contains("PKEY_AppUserModel_ID") || script.contains("pid = 5"), script)
        assertTrue(script.contains(".Save(") || script.contains("link.Save"), script)
        // 路径与 AUMID 作为单引号字面量进来（中文路径/撇号都安全）。
        assertTrue(script.contains(ShortcutAumidWriter.psLiteral(lnk.absolutePath)), script)
        assertTrue(script.contains("'$aumid'"), script)
        // 失败要以非 0 退出码收场，调用方靠它判断"要不要退回新建"。
        assertTrue(script.contains("exit 1"), script)
        assertTrue(script.contains("exit 0"), script)
    }

    @Test
    fun `单引号在 PowerShell 字面量里双写转义`() {
        assertEquals("'C:\\a''b.lnk'", ShortcutAumidWriter.psLiteral("C:\\a'b.lnk"))
        assertEquals("'plain'", ShortcutAumidWriter.psLiteral("plain"))
    }

    @Test
    fun `脚本按 UTF-16LE 编码后 Base64`() {
        val script = ShortcutAumidWriter.buildScript(lnk.absolutePath, aumid)
        val encoded = ShortcutAumidWriter.encodeCommand(script)
        assertEquals(Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)), encoded)
        // 解回来必须逐字节相同（-EncodedCommand 只认 UTF-16LE）。
        assertEquals(script, String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE))
    }

    @Test
    fun `命令行用系统自带的 powershell_exe 并带 EncodedCommand`() {
        val command = ShortcutAumidWriter.commandLine("QUJD")
        assertTrue(command.any { it.contains("powershell.exe", ignoreCase = true) }, command.toString())
        // 不依赖 pwsh（只在开发机上常见）。
        assertTrue(command.none { it.contains("pwsh", ignoreCase = true) }, command.toString())
        assertTrue(command.contains("-NoProfile"), command.toString())
        assertTrue(command.contains("-NonInteractive"), command.toString())
        assertEquals("QUJD", command.last())
        assertEquals("-EncodedCommand", command[command.size - 2])
    }

    @Test
    fun `超时留足余量且不超过启动可忍受范围`() {
        assertTrue(ShortcutAumidWriter.TIMEOUT_MS in 5_000..30_000, "${ShortcutAumidWriter.TIMEOUT_MS}")
    }
}
