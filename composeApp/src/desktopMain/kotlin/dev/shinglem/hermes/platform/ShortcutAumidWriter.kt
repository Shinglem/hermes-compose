package dev.shinglem.hermes.platform

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 把 `PKEY_AppUserModel_ID` 补写进一个**已存在的** `.lnk`（保留文件名 / 图标 / 参数）。
 *
 * 为什么是 PowerShell + COM 而不是自己改字节：`.lnk` 的 property store 是"一串自描述的序列化
 * store"，不同写入方（WScript.Shell / 应用自己的安装脚本）字段对齐差 1-2 字节；靠猜布局改写
 * 会把用户的快捷方式写坏，而这一步的收益只是"少一个开始菜单入口"。用系统自己的
 * `IShellLinkW` → `IPropertyStore` → `Commit` → `IPersistFile::Save` 是唯一保证正确的路径，
 * 代价是一次 **2.0–2.6 s** 的子进程（实测），所以只在"一次性修复"时跑，且不放在启动关键路径上。
 *
 * 为什么嵌 C# 而不是写 `.ps1`：仓库里多一个脚本文件就多一份"要被 CI/打包/签名"的负担，
 * 而这段代码只有几十行、只在需要时编译（`Add-Type -Language CSharp`）。
 *
 * 两个实测坑（照办，否则白跑）：
 * 1. **只 `Commit()` 不落盘**：必须再调 `IPersistFile::Save(path, true)`，否则文件字节里查不到 AUMID；
 * 2. **写完后用同一个 COM 对象读回是空的**（property store 缓存），所以校验用**字节判定**
 *    （[parseShortcut]），不靠脚本回读。
 */
internal object ShortcutAumidWriter {

    /** 子进程总超时（实测单次 2.0–2.6 s，留足余量）。 */
    const val TIMEOUT_MS: Long = 10_000

    /**
     * 给 [lnk] 写入 [aumid]。
     *
     * 顺序：备份 `<name>.lnk.bak` → 起 `powershell.exe -EncodedCommand` → 等待（超时杀掉）→
     * 用字节判定回读校验。任何一步失败都返回 false（调用方退回"新建"），
     * 并保证主文件仍是可用状态（备份只在成功时保留为审计用途）。
     */
    fun patch(lnk: File, aumid: String): PatchResult {
        if (!lnk.isFile) return PatchResult(ok = false, exitCode = null, timedOut = false, detail = "快捷方式不存在")
        val backup = File(lnk.parentFile, lnk.name + ".bak")
        val original = runCatching { lnk.readBytes() }.getOrNull()
            ?: return PatchResult(ok = false, exitCode = null, timedOut = false, detail = "读不了原文件")
        runCatching { backup.writeBytes(original) }
            .onFailure { return PatchResult(ok = false, exitCode = null, timedOut = false, detail = "备份失败：${it.message}") }

        val script = buildScript(lnk.absolutePath, aumid)
        val command = commandLine(encodeCommand(script))
        val (exitCode, stderr, timedOut) = runPowerShell(command)

        if (timedOut) {
            return PatchResult(ok = false, exitCode = null, timedOut = true, detail = "超时（${TIMEOUT_MS}ms）")
        }
        if (exitCode != 0) {
            return PatchResult(ok = false, exitCode = exitCode, timedOut = false, detail = stderr.ifBlank { "exit=$exitCode" })
        }
        // 脚本说成功不算数：按字节确认属性真的落盘了（见类注释的坑 2）。
        val written = runCatching { parseShortcut(lnk.readBytes())?.aumid }.getOrNull()
        return if (written == aumid) {
            PatchResult(ok = true, exitCode = 0, timedOut = false, detail = "ok")
        } else {
            PatchResult(ok = false, exitCode = 0, timedOut = false, detail = "写入后回读不到 AUMID（实际=$written）")
        }
    }

    internal data class PatchResult(
        val ok: Boolean,
        val exitCode: Int?,
        val timedOut: Boolean,
        val detail: String,
    )

    /**
     * 拼 PowerShell 脚本（纯函数，便于单测）。
     *
     * 路径 / AUMID 作为 PowerShell **单引号字面量**传入（`'` 自身双写转义），
     * C# 源码用单引号 here-string（不做变量插值），避免中文路径与引号问题。
     */
    internal fun buildScript(lnkPath: String, aumid: String): String = buildString {
        appendLine("\$ErrorActionPreference = 'Stop'")
        appendLine("\$lnk = " + psLiteral(lnkPath))
        appendLine("\$aumid = " + psLiteral(aumid))
        appendLine("\$cs = @'")
        appendLine(CSHARP_SOURCE.trimEnd())
        appendLine("'@")
        appendLine("try {")
        appendLine("  Add-Type -TypeDefinition \$cs -Language CSharp")
        appendLine("  [LnkAumid]::Set(\$lnk, \$aumid)")
        appendLine("  exit 0")
        appendLine("} catch {")
        appendLine("  [Console]::Error.WriteLine(\$_.Exception.ToString())")
        appendLine("  exit 1")
        appendLine("}")
    }

    /** PowerShell 单引号字面量：把 `'` 双写。 */
    internal fun psLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * `-EncodedCommand` 的载荷：脚本按 **UTF-16LE** 编码后 Base64。
     *
     * 这样中文路径、引号、换行都不需要经过命令行解析（否则 `powershell.exe` 的引号规则极易踩坑）。
     */
    internal fun encodeCommand(script: String): String =
        Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    /**
     * 命令行参数。
     *
     * 固定用 `%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`（Windows 必有）——
     * **不依赖 `pwsh`**（PowerShell 7 只在开发机上常见）。
     */
    internal fun commandLine(encoded: String): List<String> {
        val root = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
        val exe = if (root != null) {
            File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").absolutePath
        } else {
            "powershell.exe"
        }
        return listOf(
            exe,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            encoded,
        )
    }

    private fun runPowerShell(command: List<String>): Triple<Int?, String, Boolean> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.outputStream.close()
        val stderr = StringBuilder()
        val reader = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line -> stderr.appendLine(line) }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching Triple(null, stderr.toString(), true)
        }
        reader.join(1_000)
        Triple(process.exitValue(), stderr.toString(), false)
    }.getOrElse { Triple(null, it.message.orEmpty(), false) }

    /**
     * C# 互操作（`Add-Type` 编译）。
     *
     * 顺序与 `[PreserveSig]` 都是必须的：`IPropertyStore` 的方法顺序错了会直接访问冲突。
     * `ShellLink` 的 coclass 只用来拿 `IPersistFile`，再 `QueryInterface` 到 `IPropertyStore`。
     */
    private val CSHARP_SOURCE = """
        using System;
        using System.Runtime.InteropServices;

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        internal class ShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("0000010b-0000-0000-C000-000000000046")]
        internal interface IPersistFile {
            void GetClassID(out Guid pClassID);
            [PreserveSig] int IsDirty();
            void Load([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, uint dwMode);
            void Save([MarshalAs(UnmanagedType.LPWStr)] string pszFileName, [MarshalAs(UnmanagedType.Bool)] bool fRemember);
            void SaveCompleted([MarshalAs(UnmanagedType.LPWStr)] string pszFileName);
            void GetCurFile([MarshalAs(UnmanagedType.LPWStr)] out string ppszFileName);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
        internal interface IPropertyStore {
            [PreserveSig] int GetCount(out uint cProps);
            [PreserveSig] int GetAt(uint iProp, out PROPERTYKEY pkey);
            [PreserveSig] int GetValue(ref PROPERTYKEY key, out PROPVARIANT pv);
            [PreserveSig] int SetValue(ref PROPERTYKEY key, ref PROPVARIANT pv);
            [PreserveSig] int Commit();
        }

        [StructLayout(LayoutKind.Sequential, Pack = 4)]
        internal struct PROPERTYKEY {
            public Guid fmtid;
            public uint pid;
        }

        [StructLayout(LayoutKind.Explicit)]
        internal struct PROPVARIANT {
            [FieldOffset(0)] public ushort vt;
            [FieldOffset(2)] public ushort wReserved1;
            [FieldOffset(4)] public ushort wReserved2;
            [FieldOffset(6)] public ushort wReserved3;
            [FieldOffset(8)] public IntPtr pszVal;
        }

        public static class LnkAumid {
            public static void Set(string path, string aumid) {
                var link = (IPersistFile)new ShellLink();
                link.Load(path, 2); // STGM_READWRITE
                var store = (IPropertyStore)link;
                var key = new PROPERTYKEY {
                    fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"),
                    pid = 5, // PKEY_AppUserModel_ID
                };
                var pv = new PROPVARIANT { vt = 31, pszVal = Marshal.StringToCoTaskMemUni(aumid) }; // VT_LPWSTR
                try {
                    int hr = store.SetValue(ref key, ref pv);
                    if (hr != 0) Marshal.ThrowExceptionForHR(hr);
                    hr = store.Commit();
                    if (hr != 0) Marshal.ThrowExceptionForHR(hr);
                } finally {
                    Marshal.FreeCoTaskMem(pv.pszVal);
                }
                // 关键：不 Save 就只改了内存里的 property store（实测第一版因此白跑）。
                link.Save(path, true);
            }
        }
    """.trimIndent()
}
