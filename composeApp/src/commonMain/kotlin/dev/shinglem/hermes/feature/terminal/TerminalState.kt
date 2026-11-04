package dev.shinglem.hermes.feature.terminal

import dev.shinglem.hermes.core.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** 标签来源：用户开的 / agent 后台进程镜像（只读）。 */
@Serializable
enum class TerminalTabKind {
    @SerialName("user")
    User,

    @SerialName("agent")
    Agent,
}

@Serializable
data class TerminalTabVm(
    val id: String,
    val title: String = "",
    val cwd: String? = null,
    val kind: TerminalTabKind = TerminalTabKind.User,
)

/**
 * 终端面板的可持久化布局。**进程不随它复活**（与官方一致，见 ADR-041）：
 * 恢复时只重建标签与工作目录，shell 是新起的。
 */
@Serializable
data class TerminalLayout(
    val expanded: Boolean = false,
    val heightFraction: Float = 0.4f,
    val activeTabId: String? = null,
    val tabs: List<TerminalTabVm> = emptyList(),
)

data class TerminalPanelState(
    val layout: TerminalLayout = TerminalLayout(),
    /** 当前存活进程数（收起条上的状态点）。 */
    val running: Int = 0,
    /** agent 后台进程的只读输出（key = process_id）；不进持久化。 */
    val mirrors: Map<String, String> = emptyMap(),
) {
    val activeTab: TerminalTabVm?
        get() = layout.tabs.firstOrNull { it.id == layout.activeTabId } ?: layout.tabs.firstOrNull()

    val hasTabs: Boolean get() = layout.tabs.isNotEmpty()
}

/**
 * 终端状态持有者（全局单例）：终端不属于会话，切 profile / 切模式都不销毁。
 *
 * 只做状态与持久化；真正的 PTY 在 `:modules:terminal`（JediTerm + Pty4J）。
 */
class TerminalViewModel(private val preferences: AppPreferences) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var nextId: Int = 1

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<TerminalPanelState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(restoreSettings())
    /** 终端外观（字体 / 字号 / 滚动行数 / 配色）。设置页改它，`TerminalContent` 消费它。 */
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    /** 改外观并落盘；`fontSize` / `scrollbackLines` 会被夹到允许区间。 */
    fun updateSettings(transform: (TerminalSettings) -> TerminalSettings) {
        val next = transform(_settings.value).normalized()
        if (next == _settings.value) return
        _settings.value = next
        preferences.terminalSettings = runCatching { json.encodeToString(next) }.getOrNull()
    }

    /**
     * `terminal.read` 的实现体：由 desktop 侧注入（真正读 JediTerm 的 `TerminalTextBuffer`）。
     * commonMain 不依赖 Swing/AWT，所以这里只留函数位。
     */
    var readBuffer: ((start: Int?, count: Int?) -> TerminalReadPayload?)? = null

    /**
     * `terminal.resize` 的上报口：由 desktop 侧注入真正的 RPC（内部自己起协程）。
     *
     * 后端只把它记在会话上（`session["cols"]`）用于服务端渲染宽度估算，
     * 所以调用是 best-effort —— 失败不提示用户。
     */
    var reportCols: ((sessionId: String, cols: Int) -> Unit)?
        get() = colsReporter.send
        set(value) { colsReporter.send = value }

    private val colsReporter = ColsReporter()

    /** 当前会话变了才上报列宽（`terminal.resize` 是按会话记的）。 */
    fun onSessionChanged(next: String?) = colsReporter.onSessionChanged(next)

    /** 终端可见列数变化：同一会话同列数只上报一次。 */
    fun onColumns(cols: Int) = colsReporter.onColumns(cols)

    fun toggle() {
        if (_state.value.layout.expanded) collapse() else expand()
    }

    /** 展开面板；没有标签时自动开一个（与官方一致：打开终端就有 shell）。 */
    fun expand() {
        val current = _state.value.layout
        if (current.tabs.isEmpty()) {
            val tab = TerminalTabVm(id = "term-${nextId++}", cwd = null)
            update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id, expanded = true) }
        } else {
            update { it.copy(expanded = true) }
        }
    }

    fun collapse() = update { it.copy(expanded = false) }

    fun setHeightFraction(fraction: Float) {
        val clamped = fraction.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        update { it.copy(heightFraction = (clamped * 100).roundToInt() / 100f) }
    }

    fun setRunning(count: Int) {
        _state.update { it.copy(running = count) }
    }

    /** 新建标签；返回新标签（调用方据此请求焦点）。 */
    fun newTab(cwd: String? = null): TerminalTabVm {
        val tab = TerminalTabVm(id = "term-${nextId++}", title = "", cwd = cwd)
        update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id, expanded = true) }
        return tab
    }

    fun selectTab(tabId: String) = update { it.copy(activeTabId = tabId) }

    /** 循环切到相邻标签（`Ctrl+Tab` / `Ctrl+Shift+Tab`）；面板收起时先展开。 */
    fun cycleTab(delta: Int) {
        val current = _state.value.layout
        if (current.tabs.isEmpty()) {
            expand()
            return
        }
        val index = current.tabs.indexOfFirst { it.id == current.activeTabId }
        val size = current.tabs.size
        val next = ((if (index < 0) 0 else index) + delta).mod(size)
        update { it.copy(activeTabId = it.tabs[next].id, expanded = true) }
    }

    /** 用户主动关闭；同时处理「关掉最后一个就收起面板」。 */
    fun closeTab(tabId: String) {
        update { current ->
            val tabs = current.tabs.filterNot { it.id == tabId }
            val active = current.activeTabId
                ?.takeIf { id -> tabs.any { it.id == id } }
                ?: tabs.firstOrNull()?.id
            current.copy(
                tabs = tabs,
                activeTabId = active,
                expanded = if (tabs.isEmpty()) false else current.expanded,
            )
        }
    }

    /** 进程自己退出（exit / Ctrl-D / 崩溃）：移除标签。 */
    fun onProcessExit(tabId: String) = closeTab(tabId)

    fun renameTab(tabId: String, title: String) {
        if (title.isBlank()) return
        update { current ->
            current.copy(
                tabs = current.tabs.map { if (it.id == tabId) it.copy(title = title) else it },
            )
        }
    }

    // ---------------------------------------------------------------- agent 镜像标签

    /** agent 后台进程输出：没有对应标签就建一个只读镜像标签，有就追加（上限 [MIRROR_LIMIT]）。 */
    fun appendMirror(processId: String, chunk: String) {
        if (processId.isBlank() || chunk.isEmpty()) return
        val tabId = mirrorTabId(processId)
        _state.update { current ->
            val existing = current.mirrors[processId].orEmpty()
            val merged = (existing + chunk).let {
                if (it.length > MIRROR_LIMIT) it.takeLast(MIRROR_LIMIT) else it
            }
            val hasTab = current.layout.tabs.any { it.id == tabId }
            val layout = if (hasTab) {
                current.layout
            } else {
                current.layout.copy(
                    tabs = current.layout.tabs + TerminalTabVm(
                        id = tabId,
                        title = processId.take(8),
                        kind = TerminalTabKind.Agent,
                    ),
                )
            }
            current.copy(layout = layout, mirrors = current.mirrors + (processId to merged))
        }
    }

    /** `terminal.close`：只移除视图，不杀进程。 */
    fun closeMirror(processId: String) {
        val tabId = mirrorTabId(processId)
        _state.update { current ->
            current.copy(
                layout = current.layout.copy(
                    tabs = current.layout.tabs.filterNot { it.id == tabId },
                    activeTabId = current.layout.activeTabId?.takeIf { it != tabId },
                ),
                mirrors = current.mirrors - processId,
            )
        }
    }

    fun mirrorText(processId: String): String = _state.value.mirrors[processId].orEmpty()

    private fun mirrorTabId(processId: String): String = "agent-$processId"

    private fun update(transform: (TerminalLayout) -> TerminalLayout) {
        _state.update { current ->
            val next = transform(current.layout)
            persist(next)
            current.copy(layout = next)
        }
    }

    private fun persist(layout: TerminalLayout) {
        // agent 镜像标签是运行态观察，不进持久化（重启后它已经没意义）。
        val persisted = layout.copy(tabs = layout.tabs.filter { it.kind == TerminalTabKind.User })
        preferences.terminalLayout = runCatching { json.encodeToString(persisted) }.getOrNull()
    }

    private fun restoreSettings(): TerminalSettings {
        val raw = preferences.terminalSettings ?: return TerminalSettings()
        return runCatching { json.decodeFromString<TerminalSettings>(raw) }
            .getOrDefault(TerminalSettings())
            .normalized()
    }

    private fun restore(): TerminalPanelState {
        val raw = preferences.terminalLayout ?: return TerminalPanelState()
        val layout = runCatching { json.decodeFromString<TerminalLayout>(raw) }.getOrNull()
            ?: return TerminalPanelState()
        val maxId = layout.tabs.mapNotNull { it.id.removePrefix("term-").toIntOrNull() }.maxOrNull() ?: 0
        nextId = maxId + 1
        // 恢复时不带 agent 镜像标签（那是一次性的运行态观察）。
        val tabs = layout.tabs.filter { it.kind == TerminalTabKind.User }
        val active = layout.activeTabId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id
        return TerminalPanelState(
            layout = layout.copy(
                tabs = tabs,
                activeTabId = active,
                expanded = layout.expanded && tabs.isNotEmpty(),
            ),
        )
    }

    companion object {
        const val MIN_HEIGHT = 0.2f
        const val MAX_HEIGHT = 0.7f
        const val DEFAULT_HEIGHT = 0.4f

        /** 单个镜像标签保留的输出上限（超出丢最早的），避免 agent 长跑把内存吃满。 */
        const val MIRROR_LIMIT = 256 * 1024
    }
}

/**
 * `terminal.resize` 的上报节奏：按会话去重。
 *
 * 拖拽窗口时 Compose 会连发尺寸变化，后端只需要"最终列数"，所以同会话同列数只发一次；
 * 换会话必须重发（后端是按会话记 `cols` 的）。
 */
internal class ColsReporter(var send: ((sessionId: String, cols: Int) -> Unit)? = null) {

    private var sessionId: String? = null
    private var last: Pair<String, Int>? = null

    fun onSessionChanged(next: String?) {
        if (next == sessionId) return
        sessionId = next
        last = null
    }

    fun onColumns(cols: Int) {
        if (cols <= 0) return
        val sid = sessionId ?: return
        if (last == sid to cols) return
        last = sid to cols
        send?.invoke(sid, cols)
    }
}
