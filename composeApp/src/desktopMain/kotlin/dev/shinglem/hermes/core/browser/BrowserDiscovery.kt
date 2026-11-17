package dev.shinglem.hermes.core.browser

import dev.shinglem.hermes.core.log.Log
import java.io.File
import java.io.File.separatorChar

/** 找到的一只 agent 浏览器。 */
internal data class BrowserTarget(
    val pid: Long,
    val hwnd: Long,
    val port: Int,
    val userDataDir: String,
    val ephemeral: Boolean,
) {
    /** 是否拿到了可嵌入的窗口（headless 实例没有）。 */
    val embeddable: Boolean get() = hwnd != 0L
}

/** 一次发现的结论。**四种"没找到"必须分开**，它们的处置完全不同。 */
internal sealed interface DiscoveryOutcome {
    /**
     * 找到了至少一只可纳管的。
     *
     * 后端每个 launcher key 一只浏览器（不同会话 / 不同 profile 各一只），所以是**列表**。
     */
    data class Found(val targets: List<BrowserTarget>) : DiscoveryOutcome

    /** records 目录里没有活着的记录：agent 还没起过浏览器。 */
    data object NoRecord : DiscoveryOutcome

    /** 有记录且进程活着，但没有可见顶层窗口 —— headless 模式。 */
    data class Headless(val pid: Long) : DiscoveryOutcome

    /** 平台不支持（非 Windows）。 */
    data object Unsupported : DiscoveryOutcome
}

/**
 * 找到 agent 正在用的那只 Chromium（ADR-082）。
 *
 * ### 数据源（实测确认）
 *
 * 1. `<HERMES_HOME>/cache/browser/chromium-records 目录下的 json` —— 后端 `browser_launcher` 每起一只就写一条；
 * 2. 记录的 `user_data_dir/DevToolsActivePort` —— 拿端口；
 * 3. `Win32WindowQuery.topLevelWindow(pid)` —— 拿顶层 HWND。
 *
 * **为什么不用 `browser.cdp_url`**：它只在用户手配或 `/browser connect` 时才有值。
 *
 * ### 排序规则
 *
 * 同一时刻可能有多只（每个 launcher key 一只、或多 profile）。选：
 * 1. 进程还活着的（`pid` 存在）；
 * 2. 在其中优先 `owner` 也活着且 `start_time` 为最新的 —— 后起的通常是当前正在用的那只。
 *
 * ### 陈旧记录
 *
 * 后端自己的 `reap_orphaned_browsers` 会清，但它只在后端启动时跑。我们这边必须自己判：
 * `pid` 不在 = 记录陈旧，跳过（**不删**，那是后端的文件）。
 */
internal class BrowserDiscovery(
    /** 本程序的 `HERMES_HOME`（后端那个），records 在它下面的 `cache/browser/chromium-records`。 */
    private val hermesHome: File,
    /** `pid` 是否还活着。测试注入。 */
    private val isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
    /** 读 `DevToolsActivePort` 第一行；返回 0 表示读不到。测试注入。 */
    private val readPort: (String) -> Int = { dir -> DevToolsPortFile.read(dir)?.port ?: 0 },
    /** `pid` → 顶层 HWND；0 表示没有窗口。测试注入。 */
    private val findWindow: (Long) -> Long = { pid -> Win32WindowQuery.topLevelWindow(pid) },
    private val supported: Boolean = Win32WindowQuery.supported,
) {

    private val recordsDir: File
        get() = File(File(File(hermesHome, "cache"), "browser"), "chromium-records")

    fun discover(): DiscoveryOutcome {
        if (!supported) return DiscoveryOutcome.Unsupported

        val records = readRecords()
        if (records.isEmpty()) return DiscoveryOutcome.NoRecord

        val live = records.filter { isAlive(it.pid) }
        if (live.isEmpty()) return DiscoveryOutcome.NoRecord

        // 后起的在前：同一批里 start_time 大的优先（列表顺序 = 抽屉里的展示顺序）。
        val ordered = live.sortedByDescending { it.startTimeEpochMs ?: 0L }

        val found = mutableListOf<BrowserTarget>()
        var headlessPid: Long? = null
        val seenPid = mutableSetOf<Long>()
        for (record in ordered) {
            // 同一 pid 可能有多条 record（后端换 key 时会写新记录）；只认第一条。
            if (!seenPid.add(record.pid)) continue
            val port = readPort(record.userDataDir)
            val hwnd = findWindow(record.pid)
            if (hwnd == 0L) {
                if (headlessPid == null) headlessPid = record.pid
                continue
            }
            found += BrowserTarget(
                pid = record.pid,
                hwnd = hwnd,
                port = port,
                userDataDir = record.userDataDir,
                ephemeral = record.ephemeral,
            )
        }

        if (found.isNotEmpty()) return DiscoveryOutcome.Found(found)

        // 有活进程但都没有窗口 —— 可能是 headless，也可能窗口正被我们自己嵌成 child window
        // （`NativeView` 收进去之后就不在顶层枚举链里了）。两者都报 `Headless(pid)`：
        // 调用方在已纳管时**不会**因为这一条而丢列表（判活只问 `isSlotAlive`）。
        return DiscoveryOutcome.Headless(headlessPid ?: ordered.first().pid)
    }

    private fun readRecords(): List<ChromiumRecord.Parsed> {
        val dir = recordsDir
        if (!dir.isDirectory) return emptyList()
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        }.onFailure {
            Log.warn("browser discovery: 读 records 目录失败：${it.message}")
        }.getOrDefault(emptyArray())

        return files.mapNotNull { file ->
            runCatching { ChromiumRecord.parse(file.readText()) }
                .onFailure { Log.warn("browser discovery: 解析 ${file.name} 失败：${it.message}") }
                .getOrNull()
        }
    }

    /** 只用于日志/诊断：records 目录的绝对路径。 */
    val recordsPath: String get() = recordsDir.absolutePath.replace(separatorChar, '/')

    /**
     * 是否存在任何 launch record（**廉价**：只列目录）。
     *
     * 心跳每 5s 跑一次，agent 从没起过浏览器时不该每次都读文件 + 枚举窗口。
     */
    fun hasAnyRecord(): Boolean {
        val dir = recordsDir
        if (!dir.isDirectory) return false
        return runCatching { dir.list()?.any { it.endsWith(".json") } == true }.getOrDefault(false)
    }
}
