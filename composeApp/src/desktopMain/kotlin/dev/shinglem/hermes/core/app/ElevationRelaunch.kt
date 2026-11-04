package dev.shinglem.hermes.core.app

import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * 以管理员身份重新拉起自己（`runas`）。
 *
 * 等价于资源管理器里的「以管理员身份运行」，是 Windows 唯一受支持的提权路径 ——
 * 不自己造 UAC 绕过。调用方必须是**尚未持有单实例互斥量**的早期阶段
 * （见 `08-单实例与启动时序.md` §5，否则会与提权后的新进程互相唤醒成活锁）。
 *
 * 返回值语义：
 * - [Outcome.STARTED]：新进程已拉起，当前进程应当退出
 * - [Outcome.CANCELLED]：用户点了"否"（`ERROR_CANCELLED`）—— **不是失败**，调用方应以普通权限继续
 * - [Outcome.FAILED]：其它错误（`ShellExecuteExW` 失败、取不到命令行等）
 */
internal object ElevationRelaunch {

    enum class Outcome { STARTED, CANCELLED, FAILED }

    private const val ERROR_CANCELLED = 1223
    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
    private const val SEE_MASK_NOASYNC = 0x00000100
    private const val SEE_MASK_FLAG_NO_UI = 0x00000400
    private const val SW_SHOWNORMAL = 1

    /** `SHELLEXECUTEINFOW` 在 x64 上是 112 字节（最后一个字段是 `hProcess`）。 */
    private const val INFO_SIZE = 112L

    fun relaunch(args: Array<String>, workingDir: String? = null): Outcome {
        val info = currentCommandLine(args)
        if (info.isEmpty()) {
            Log.warn("提权重启失败：拿不到当前进程的命令行")
            return Outcome.FAILED
        }
        return execute("runas", info.first(), info.drop(1).joinToString(" ") { quoteArgument(it) }, workingDir)
    }

    /**
     * 测试用：用普通 `open` 启动一个进程。
     *
     * 存在的理由是 `runas` 必须人工点 UAC（安全桌面，脚本注入无效），没法自动测；
     * 而 `SHELLEXECUTEINFOW` 的字段偏移、UTF-16 字符串、返回值处理恰恰是最容易写错的地方。
     * 用 `open` 走同一条代码路径，能把"结构写错"这类问题在单测里挡掉。
     */
    internal fun launchForTest(exe: String, parameters: String): Outcome = execute("open", exe, parameters, null)

    private fun execute(verb: String, exe: String, parameters: String, workingDir: String?): Outcome {
        val api = Api.instance ?: return Outcome.FAILED
        return when (val code = api.shellExecuteEx(verb, exe, parameters, workingDir)) {
            0 -> Outcome.STARTED
            ERROR_CANCELLED -> Outcome.CANCELLED
            else -> {
                Log.warn("提权重启失败：ShellExecuteExW($verb) 返回 $code")
                Outcome.FAILED
            }
        }
    }

    /**
     * 当前进程的完整命令行。优先 `ProcessHandle`，拿不到再走 Win32 ——
     * 与"重启客户端"共用同一套还原逻辑（`WindowsCommandLine`），那里有更详细的说明。
     */
    private fun currentCommandLine(args: Array<String>): List<String> {
        val handleInfo = ProcessHandle.current().info()
        val command = handleInfo.command().orElse(null)
        val arguments = handleInfo.arguments().orElse(emptyArray())
        if (command != null && arguments.isNotEmpty()) return listOf(command) + arguments
        WindowsCommandLine.current()?.takeIf { it.isNotEmpty() }?.let { return it }
        // 最差情况：知道 exe 与 main class，但丢了 JVM 参数 —— 比什么都不做强，且会记进日志。
        Log.warn("提权重启：命令行不完整，退回 exe + 参数 ${args.joinToString(" ")}")
        return command?.let { listOf(it) + args } ?: emptyList()
    }

    /**
     * 按 MSVCRT 的解析规则给参数加引号（`CommandLineToArgvW` 的逆运算）。
     *
     * 含空格/制表符/引号才加引号；结尾的连续反斜杠要翻倍，引号前的反斜杠也要翻倍 ——
     * 便携包放在 `D:\My Apps\Hermes` 时 `-cp` 的值必然含空格，这一层不做就会启动失败。
     */
    internal fun quoteArgument(argument: String): String {
        if (argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '"' }) return argument
        val builder = StringBuilder("\"")
        var backslashes = 0
        for (char in argument) {
            when (char) {
                '\\' -> backslashes += 1
                '"' -> {
                    builder.append("\\".repeat(backslashes * 2 + 1))
                    builder.append('"')
                    backslashes = 0
                }

                else -> {
                    builder.append("\\".repeat(backslashes))
                    builder.append(char)
                    backslashes = 0
                }
            }
        }
        builder.append("\\".repeat(backslashes * 2))
        builder.append('"')
        return builder.toString()
    }

    private class Api(
        private val shellExecuteExHandle: MethodHandle,
        private val closeHandleHandle: MethodHandle,
    ) {
        companion object {
            private val linker = Linker.nativeLinker()
            private val captureLayout = Linker.Option.captureStateLayout()
            private val errorPath = java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError")

            val instance: Api? by lazy {
                runCatching {
                    // 与 Job Object / 命名对象同一个坑：defaultLookup() 找不到 shell32 的导出。
                    val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
                    val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
                    val capture = Linker.Option.captureCallState("GetLastError")
                    val shellExecuteEx = shell32.find("ShellExecuteExW").orElse(null) ?: return@runCatching null
                    val closeHandle = kernel32.find("CloseHandle").orElse(null) ?: return@runCatching null
                    Api(
                        shellExecuteExHandle = linker.downcallHandle(
                            shellExecuteEx,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            capture,
                        ),
                        closeHandleHandle = linker.downcallHandle(
                            closeHandle,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        ),
                    )
                }.getOrNull()
            }

            fun captureLayout(): java.lang.foreign.MemoryLayout = captureLayout

            /** `GetLastError` 在捕获状态段里的偏移。 */
            val errorOffset: Long = captureLayout.byteOffset(errorPath)
        }

        /**
         * 调 `ShellExecuteExW`。成功返回 0，失败返回 `GetLastError()`（用户取消是 1223）。
         *
         * 结构按 x64 偏移直接写：
         * ```
         *  0 cbSize(DWORD)   4 fMask(DWORD)   8 hwnd(ptr)    16 lpVerb(ptr)
         * 24 lpFile(ptr)    32 lpParameters   40 lpDirectory 48 nShow(INT)
         * 104 hProcess(ptr) ← ShellExecuteEx 写回新进程句柄，必须 CloseHandle
         * ```
         */
        fun shellExecuteEx(verb: String, file: String, parameters: String, workingDir: String?): Int {
            val arena = Arena.ofShared()
            try {
                val state = arena.allocate(captureLayout)
                val info = arena.allocate(INFO_SIZE)
                info.set(ValueLayout.JAVA_INT, 0, INFO_SIZE.toInt())
                info.set(
                    ValueLayout.JAVA_INT,
                    4,
                    SEE_MASK_NOCLOSEPROCESS or SEE_MASK_NOASYNC or SEE_MASK_FLAG_NO_UI,
                )
                info.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL)
                info.set(ValueLayout.ADDRESS, 16, arena.allocateFrom(verb, Charsets.UTF_16LE))
                info.set(ValueLayout.ADDRESS, 24, arena.allocateFrom(file, Charsets.UTF_16LE))
                info.set(
                    ValueLayout.ADDRESS,
                    32,
                    if (parameters.isEmpty()) MemorySegment.NULL else arena.allocateFrom(parameters, Charsets.UTF_16LE),
                )
                info.set(
                    ValueLayout.ADDRESS,
                    40,
                    workingDir?.takeIf { it.isNotBlank() }
                        ?.let { arena.allocateFrom(it, Charsets.UTF_16LE) }
                        ?: MemorySegment.NULL,
                )
                info.set(ValueLayout.JAVA_INT, 48, SW_SHOWNORMAL)
                info.set(ValueLayout.ADDRESS, 104, MemorySegment.NULL)

                val ok = (shellExecuteExHandle.invokeWithArguments(state, info) as Int) != 0
                if (ok) {
                    val process = info.get(ValueLayout.ADDRESS, 104)
                    if (process.address() != 0L) runCatching { closeHandleHandle.invokeWithArguments(process) }
                    return 0
                }
                return state.get(ValueLayout.JAVA_INT, errorOffset)
            } finally {
                arena.close()
            }
        }
    }
}
