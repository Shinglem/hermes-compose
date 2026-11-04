package dev.shinglem.hermes.terminal

import ai.rever.bossterm.compose.columns
import ai.rever.bossterm.compose.createTerminalTab as createBossTab
import ai.rever.bossterm.compose.selectedText
import ai.rever.bossterm.compose.snapshot
import ai.rever.bossterm.compose.tailText
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings as BossSettings
import ai.rever.bossterm.compose.startTerminalProcess
import ai.rever.bossterm.compose.tabs.TerminalTab as BossTab
import ai.rever.bossterm.compose.ui.ProperTerminal
import ai.rever.bossterm.compose.util.loadTerminalFont
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一个终端标签的视图模型（Compose 侧只需要 id 与启动参数）。 */
data class TerminalTab(
    val id: String,
    val cwd: String? = null,
    val command: List<String>? = null,
)

/** 终端外观设置（来自我们的 `state.json`）。颜色是 ARGB。 */
data class TerminalViewSettings(
    val fontFamily: String? = null,
    val fontSize: Float = 14f,
    val scrollbackLines: Int = 5_000,
    val foregroundArgb: Int = 0xFFD5DBE5.toInt(),
    val backgroundArgb: Int = 0xFF05070B.toInt(),
)

private fun TerminalViewSettings.toBossSettings(): BossSettings = BossSettings(
    fontSize = fontSize,
    fontName = fontFamily,
    bufferMaxLines = scrollbackLines,
    defaultForeground = "0x" + foregroundArgb.toUInt().toString(16).padStart(8, '0').uppercase(),
    defaultBackground = "0x" + backgroundArgb.toUInt().toString(16).padStart(8, '0').uppercase(),
)

private class HostedTab(val boss: BossTab, val engine: TerminalSessionEngine)

/**
 * 终端会话注册表：**与 Composition 无关**，保证收起面板 / 切模式时不杀 PTY（ADR-041）。
 * 所有条目在应用退出时才显式关闭。
 */
private object BossTermSessions {
    // Snapshot state: the composable reads this map to decide what to render, so plain
    // MutableMap would leave the first frame blank and never re-compose.
    private val entries = mutableStateMapOf<String, HostedTab>()

    fun ensure(
        spec: TerminalTab,
        settings: BossSettings,
        onExit: (String) -> Unit,
        onRunningCount: (Int) -> Unit,
    ): HostedTab = entries.getOrPut(spec.id) {
        val boss = createBossTab(settings)
        val engine = startTerminalProcess(
            tab = boss,
            settings = settings,
            command = spec.command?.joinToString(" "),
            workingDirectory = spec.cwd,
            onExit = { onExit(spec.id) },
        )
        HostedTab(boss, engine)
    }

    fun get(id: String?): HostedTab? = id?.let { entries[it] }

    fun retain(ids: Set<String>) {
        entries.keys.filterNot { it in ids }.forEach { entries.remove(it)?.engine?.close() }
    }

    fun runningCount(): Int = entries.values.count { it.engine.isAlive() }

    fun closeAll() {
        entries.values.forEach { runCatching { it.engine.close() } }
        entries.clear()
    }
}

/**
 * 终端宿主：Compose 唯一入口。
 *
 * 从 JediTerm + `SwingPanel` 换成了 BossTerm 的 Compose 原生渲染，
 * 因此不再有 heavyweight 组件遮挡 Compose 对话框的问题。
 */
@Composable
fun TerminalHost(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    settings: TerminalViewSettings,
    onExit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onColumns: (Int) -> Unit = {},
    onRunningCount: (Int) -> Unit = {},
) {
    val bossSettings = settings.toBossSettings()

    // 字体名 → Compose FontFamily。首帧先用平台等宽字体占位，解析（含 bundled 字体落盘）
    // 放到 IO 线程，否则第一次进终端会在组合线程上卡几百毫秒。
    // 留空时 loadTerminalFont 会回落 BossTerm 自带的 MesloLGS Nerd Font —— 系统等宽字体没有
    // Nerd 图标的私有区字形，oh-my-posh 之类的提示符会显示成方块。
    var terminalFont by remember { mutableStateOf<FontFamily>(FontFamily.Monospace) }
    LaunchedEffect(settings.fontFamily) {
        terminalFont = withContext(Dispatchers.IO) { loadTerminalFont(settings.fontFamily) }
    }

    // 设置改动实时生效（字号 / 配色）；回看行数（bufferMaxLines）只对新标签生效。
    LaunchedEffect(bossSettings) {
        SettingsManager.installInMemory(bossSettings)
    }

    // 标签对账：新建要起 PTY，关掉要收 PTY。key 必须包含 tabs —— 只按 settings 做 key 时，
    // 点「+」新增的标签永远不会被创建，画面就是空白。
    LaunchedEffect(tabs, bossSettings) {
        BossTermSessions.retain(tabs.map { it.id }.toSet())
        tabs.forEach { spec ->
            BossTermSessions.ensure(spec, bossSettings, onExit, onRunningCount)
        }
        onRunningCount(BossTermSessions.runningCount())
    }

    val active = BossTermSessions.get(activeTabId)
    if (active != null) {
        ProperTerminal(
            tab = active.boss,
            isActiveTab = true,
            autoFocus = true,
            sharedFont = terminalFont,
            onTabTitleChange = {},
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(modifier.fillMaxSize())
    }

    // 列数变化与存活进程数：轮询而不是依赖 buffer 的回调，1s 一次足够（viewModel 侧会去重）。
    LaunchedEffect(activeTabId) {
        while (true) {
            BossTermSessions.get(activeTabId)?.boss?.columns()?.takeIf { it > 0 }?.let(onColumns)
            onRunningCount(BossTermSessions.runningCount())
            delay(1_000)
        }
    }
}

/** 应用退出时关闭所有 PTY。 */
fun closeAllTerminalSessions() = BossTermSessions.closeAll()

/** 当前选中文本（「引用选中到输入框」用）；没有选区时为 null。 */
fun terminalSelectedText(tabId: String?): String? =
    BossTermSessions.get(tabId)?.boss?.selectedText()

/** 最近 [lines] 行文本（含回看），供引用整段输出。 */
fun terminalTailText(tabId: String?, lines: Int = 200): String =
    BossTermSessions.get(tabId)?.boss?.tailText(lines).orEmpty()

/** `terminal.read` 的返回结构（与官方字段一致，见 ADR-041）。 */
data class TerminalReadSnapshot(
    val totalLines: Int,
    val start: Int,
    val end: Int,
    val viewportRows: Int,
    val cursorRow: Int,
    val text: String,
)

/** `terminal.read` 的实现：读激活标签的缓冲区快照。 */
fun terminalRead(tabId: String?, start: Int? = null, count: Int? = null): TerminalReadSnapshot? {
    val snapshot = BossTermSessions.get(tabId)?.boss?.snapshot(start, count) ?: return null
    return TerminalReadSnapshot(
        totalLines = snapshot.totalLines,
        start = snapshot.start,
        end = snapshot.end,
        viewportRows = snapshot.viewportRows,
        cursorRow = snapshot.cursorRow,
        text = snapshot.text,
    )
}