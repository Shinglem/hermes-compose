package dev.shinglem.hermes.core.app

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * 取**当前进程**的完整命令行（含 exe 路径），用于"原样重启自己"。
 *
 * 为什么不用 `ProcessHandle.current().info().arguments()`：它在 Windows 上不保证有值，
 * 拿不到时只剩一个 `javaw.exe`，新进程会打印 java 用法说明然后退出（实测踩到 ——
 * 旧窗口关了、新窗口没起来，用户看到的是"程序消失"）。
 *
 * `GetCommandLineW` + `CommandLineToArgvW` 是启动器实际用的那条命令行，最忠实：
 * jpackage 启动器塞进来的 `-Djpackage.app-version`、`-XX:ErrorFile` 都在里面。
 */
internal object WindowsCommandLine {

    fun current(): List<String>? = runCatching { read() }.getOrNull()

    private fun read(): List<String>? {
        val api = Api.instance ?: return null
        val commandLine = api.getCommandLine() ?: return null
        val argv = api.commandLineToArgv(commandLine) ?: return null
        val (pointer, count) = argv
        return try {
            if (count <= 0) return null
            val array = pointer.reinterpret(count.toLong() * ValueLayout.ADDRESS.byteSize())
            (0 until count).map { index ->
                val argument = array.getAtIndex(ValueLayout.ADDRESS, index.toLong())
                readUtf16(argument)
            }
        } finally {
            api.localFree(pointer)
        }
    }

    private class Api(
        private val getCommandLineHandle: MethodHandle,
        private val commandLineToArgvHandle: MethodHandle,
        private val localFreeHandle: MethodHandle,
    ) {
        companion object {
            private val linker = java.lang.foreign.Linker.nativeLinker()

            val instance: Api? by lazy {
                runCatching {
                    val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
                    val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
                    val getCommandLine = kernel32.find("GetCommandLineW").orElse(null) ?: return@runCatching null
                    // `CommandLineToArgvW` 名字带 W 后缀但实际导出无后缀（shell32 的著名怪癖）。
                    val toArgv = shell32.find("CommandLineToArgvW").orElse(null) ?: return@runCatching null
                    val localFree = kernel32.find("LocalFree").orElse(null) ?: return@runCatching null
                    Api(
                        getCommandLineHandle = linker.downcallHandle(
                            getCommandLine,
                            FunctionDescriptor.of(ValueLayout.ADDRESS),
                        ),
                        commandLineToArgvHandle = linker.downcallHandle(
                            toArgv,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                        ),
                        localFreeHandle = linker.downcallHandle(
                            localFree,
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                        ),
                    )
                }.getOrNull()
            }
        }

        fun getCommandLine(): String? {
            val pointer = getCommandLineHandle.invokeWithArguments() as? MemorySegment ?: return null
            if (pointer.address() == 0L) return null
            return readUtf16(pointer)
        }

        fun commandLineToArgv(commandLine: String): Pair<MemorySegment, Int>? {
            val arena = Arena.ofShared()
            // 必须显式 UTF-16LE：`allocateFrom(String)` 默认写 UTF-8，
            // `CommandLineToArgvW` 按 UTF-16 解析就会得到一整串乱码（实测）。
            val text = arena.allocateFrom(commandLine, Charsets.UTF_16LE)
            val countSlot = arena.allocate(ValueLayout.JAVA_INT)
            val argv = commandLineToArgvHandle.invokeWithArguments(text, countSlot) as MemorySegment
            if (argv.address() == 0L) {
                arena.close()
                return null
            }
            // argv 由 LocalFree 释放，arena 只负责计数槽；留着 arena 直到读完（进程级生命周期）。
            return argv to countSlot.get(ValueLayout.JAVA_INT, 0)
        }

        fun localFree(pointer: MemorySegment) {
            runCatching { localFreeHandle.invokeWithArguments(pointer) }
        }
    }
}

/**
 * 按 UTF-16LE 读到 NUL 为止。
 *
 * 不能用 `MemorySegment.getString(0)`：无 charset 的重载按 UTF-8 解码，UTF-16 数据里
 * 每个 ASCII 字符后面的 0x00 会把它截断 —— 实测得到 "C"（exe 路径的首字母）。
 */
private fun readUtf16(pointer: MemorySegment): String {
    val bytes = pointer.reinterpret(MAX_STRING_BYTES)
    val builder = StringBuilder()
    var offset = 0L
    while (offset < MAX_STRING_BYTES) {
        val code = bytes.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset).toInt()
        if (code == 0) break
        builder.append(code.toChar())
        offset += 2
    }
    return builder.toString()
}

/** 单个参数的读取上限（防悬空指针读穿）。 */
private const val MAX_STRING_BYTES = 64L * 1024
