package dev.shinglem.hermes.core.browser

import dev.shinglem.hermes.core.log.Log
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * `pid` → 顶层窗口 `HWND`（FFM，不用 AWT）。
 *
 * ### 为什么需要它
 *
 * attach 方案的最后一跳（ADR-082 实测记录 A0）：后端 launch record 只给 `pid`，
 * 而 `NativeView` 要的是 `HWND`。
 *
 * ### 为什么不用 `Process.MainWindowHandle`
 *
 * 那是 .NET 的便利属性，JVM 没有等价物（`ProcessHandle` 不暴露窗口）。项目已有 FFM 惯例
 * （`Win32Clipboard` / `NativeMessageBox` / `ProcessAumid`），这里沿用同一种写法。
 *
 * ### 为什么不用 `EnumWindows` + upcall stub
 *
 * 那要在 FFM 里造 upcall stub，把 `pid` 与目标槽塞进 `lParam` 或线程局部 —— 回调跑在
 * 另一个栈上，闭包捕获的局部变量可见性没有保证，很容易写成"看着对但拿不到"。
 *
 * 改用**显式遍历**：`GetTopWindow(NULL)` 拿第一个顶层窗口，`GetWindow(GW_HWNDNEXT)`
 * 逐个往下走。纯同步 downcall，不需要回调。
 *
 * ### 判定规则（实测校准，pid 24644 → HWND 530110）
 *
 * Chromium 主进程有且只有一个可见的顶层窗口，其余（GPU / renderer / utility）都是
 * message-only 或不可见。四条同时满足才算：
 *
 * 1. `GetWindowThreadProcessId(hwnd) == pid`
 * 2. `IsWindowVisible(hwnd)` —— 排除不可见窗口
 * 3. `GetWindow(hwnd, GW_OWNER) == NULL` —— 排除 owned 弹窗 / 工具窗口
 * 4. `GetWindowTextLengthW(hwnd) > 0` —— Chromium 顶层窗口有标题；message-only 为 0
 *
 * ### 线程
 *
 * 无状态、可任意线程调用。
 */
internal object Win32WindowQuery {

    private const val GW_HWNDNEXT = 2
    private const val GW_OWNER = 4

    /** 遍历上限：正常桌面几十个顶层窗口，给一个远大于实际值的界防止 API 异常时死循环。 */
    private const val MAX_WINDOWS = 4096

    private val linker: Linker = Linker.nativeLinker()
    private val user32: SymbolLookup? = runCatching {
        SymbolLookup.libraryLookup("user32", Arena.global())
    }.onFailure { Log.warn("win32 window query: user32 unavailable: ${it.message}") }.getOrNull()

    private fun bind(name: String, desc: FunctionDescriptor): MethodHandle? {
        val lookup = user32 ?: return null
        val symbol = lookup.find(name).orElse(null) ?: return null
        return runCatching<MethodHandle?> { linker.downcallHandle(symbol, desc) }.getOrNull()
    }

    private val getTopWindow: MethodHandle? = bind(
        "GetTopWindow",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
    )

    private val getWindow: MethodHandle? = bind(
        "GetWindow",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT),
    )

    private val getWindowThreadProcessId: MethodHandle? = bind(
        "GetWindowThreadProcessId",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    )

    private val isWindowVisible: MethodHandle? = bind(
        "IsWindowVisible",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    )

    private val getWindowTextLength: MethodHandle? = bind(
        "GetWindowTextLengthW",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    )

    private val isWindowFn: MethodHandle? = bind(
        "IsWindow",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    )

    /** 平台是否支持（非 Windows 上句柄为 null）。 */
    val supported: Boolean
        get() = getTopWindow != null && getWindow != null && getWindowThreadProcessId != null &&
            isWindowVisible != null && getWindowTextLength != null

    /**
     * `IsWindow`：判槽位是否还有效（那只浏览器还在不在）。
     *
     * 窗口句柄可能被系统复用，所以这个只用于"明显失效"的判定 —— 真正可靠的存活判据是
     * `pid` 是否还在（见 `BrowserDiscovery`），这里只是廉价的二次确认。
     */
    fun isWindow(hwnd: Long): Boolean {
        if (hwnd == 0L) return false
        val fn = isWindowFn ?: return false
        return runCatching { (fn.invokeWithArguments(hwnd) as Int) != 0 }.getOrDefault(false)
    }

    /**
     * [hwnd] 是否仍是 [pid] 的窗口（**无论它现在是顶层还是已被 `NativeView` 收成子窗口**）。
     *
     * ### 为什么需要它（不要用 [topLevelWindow] 判活）
     *
     * `NativeView` 用 `SetParent` 把外部窗口收成 child window，此后：
     * - `GetTopWindow` 的枚举链里**不再有它**（那是顶层窗口链）；
     * - 于是 [topLevelWindow] 返回 0。
     *
     * 如果心跳用 `topLevelWindow` 判活，就会得出"浏览器没了"→ 销毁槽位 → 窗口恢复成顶层
     * → 下一轮又找到 → 反复重挂（**实测到的"一会儿纳管一会儿独立窗口"就是这个**）。
     *
     * 判活只问两件事：`IsWindow`（句柄还有效）+ `GetWindowThreadProcessId`（还属于同一个 pid）。
     */
    fun isAliveForPid(hwnd: Long, pid: Long): Boolean {
        if (hwnd == 0L || pid <= 0L) return false
        if (!isWindow(hwnd)) return false
        return ownerOf(hwnd) == pid
    }

    /**
     * 找 [pid] 的顶层窗口；找不到返回 0。
     *
     * 失败（不支持 / 异常 / 没有可见窗口）都返回 0 —— 调用方据此区分
     * `NoBrowser`（进程没了）与 `Headless`（进程在但没有窗口）。
     */
    fun topLevelWindow(pid: Long): Long {
        if (pid <= 0L || !supported) return 0L
        return runCatching { find(pid) }
            .onFailure { Log.warn("win32 window query: enumerate failed: ${it.message}") }
            .getOrDefault(0L)
    }

    private fun find(pid: Long): Long {
        val top = getTopWindow ?: return 0L
        val next = getWindow ?: return 0L

        var hwnd = top.invokeWithArguments(0L) as Long
        var steps = 0
        while (hwnd != 0L && steps < MAX_WINDOWS) {
            steps++
            if (matches(hwnd, pid)) return hwnd
            hwnd = next.invokeWithArguments(hwnd, GW_HWNDNEXT) as Long
        }
        return 0L
    }

    private fun matches(hwnd: Long, pid: Long): Boolean {
        if (ownerOf(hwnd) != pid) return false
        if (!visible(hwnd)) return false
        if (hasOwner(hwnd)) return false
        return titleLength(hwnd) > 0
    }

    private fun ownerOf(hwnd: Long): Long {
        val fn = getWindowThreadProcessId ?: return 0L
        return Arena.ofConfined().use { arena ->
            val out = arena.allocate(ValueLayout.JAVA_INT)
            fn.invokeWithArguments(hwnd, out)
            // DWORD 无符号；转 Long 时清掉符号位。
            out.get(ValueLayout.JAVA_INT, 0L).toLong() and 0xFFFF_FFFFL
        }
    }

    private fun visible(hwnd: Long): Boolean {
        val fn = isWindowVisible ?: return false
        return runCatching { (fn.invokeWithArguments(hwnd) as Int) != 0 }.getOrDefault(false)
    }

    private fun hasOwner(hwnd: Long): Boolean {
        val fn = getWindow ?: return true
        return runCatching { (fn.invokeWithArguments(hwnd, GW_OWNER) as Long) != 0L }.getOrDefault(true)
    }

    private fun titleLength(hwnd: Long): Int {
        val fn = getWindowTextLength ?: return 0
        return runCatching { fn.invokeWithArguments(hwnd) as Int }.getOrDefault(0)
    }
}
