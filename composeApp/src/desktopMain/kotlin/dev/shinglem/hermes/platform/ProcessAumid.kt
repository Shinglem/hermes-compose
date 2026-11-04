package dev.shinglem.hermes.platform

import dev.shinglem.hermes.core.log.Log
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * `SetCurrentProcessExplicitAppUserModelID`（shell32）。
 *
 * 为什么需要：`WindowsNotificationCenter.initialize` 会把**进程的 AUMID** 改成通知用的那个，
 * 而 Windows 任务栏按 AUMID 分组 —— 改完任务栏就多出一个图标（实测：固定项 + 运行窗口各一个）。
 * toast 本身靠 `CreateToastNotifier(aumid)` 显式指定，不依赖进程这个值，所以初始化后调回空串，
 * 让任务栏分组恢复原样。
 */
internal object ProcessAumid {

    private val handle = runCatching {
        val linker = Linker.nativeLinker()
        val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
        linker.downcallHandle(
            shell32.find("SetCurrentProcessExplicitAppUserModelID").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
    }.onFailure { Log.warn("process aumid: bind failed: ${it.message}") }.getOrNull()

    /**
     * 清除进程显式 AUMID。
     *
     * `NULL` 会返回 `E_INVALIDARG`（实测 0x80070057），Windows 只认空串。
     */
    fun clear() {
        val downcall = handle ?: return
        runCatching {
            Arena.ofConfined().use { arena ->
                val empty = arena.allocate(2, 2)
                empty.setString(0, "")
                val rc = downcall.invokeWithArguments(empty) as Int
                if (rc != 0) Log.warn("process aumid: clear rc=$rc")
            }
        }.onFailure { Log.warn("process aumid: clear failed: ${it.message}") }
    }
}
