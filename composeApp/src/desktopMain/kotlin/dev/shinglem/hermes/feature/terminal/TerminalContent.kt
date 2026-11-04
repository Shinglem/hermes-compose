package dev.shinglem.hermes.feature.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shinglem.hermes.terminal.TerminalHost
import dev.shinglem.hermes.terminal.TerminalTab
import dev.shinglem.hermes.terminal.TerminalViewSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 终端内容桥：把 common 侧的 [TerminalViewModel] 接到 `:modules:terminal` 的 [TerminalHost]。
 *
 * 放在 desktopMain —— 这是 commonMain 与终端实现的唯一交界处（BossTerm 是 JVM-only 的 Compose 原生渲染）。
 *
 * 两类标签在这里分流：
 * - 用户标签 → `TerminalHost`（Compose 原生渲染，PTY 常驻）；
 * - agent 镜像标签 → [MirrorTerminalView]（纯文本，**没有 PTY**）。
 */
@Composable
fun TerminalContent(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiSettings by viewModel.settings.collectAsStateWithLifecycle()
    val active = state.activeTab

    val settings = remember(uiSettings) {
        TerminalViewSettings(
            fontFamily = uiSettings.fontFamily,
            fontSize = uiSettings.fontSize,
            scrollbackLines = uiSettings.scrollbackLines,
            foregroundArgb = uiSettings.foregroundArgb,
            backgroundArgb = uiSettings.backgroundArgb,
        )
    }

    // 只有用户标签需要 PTY；镜像标签绝不能起 shell。
    val userTabs = remember(state.layout.tabs) {
        state.layout.tabs
            .filter { it.kind == TerminalTabKind.User }
            .map { TerminalTab(id = it.id, cwd = it.cwd) }
    }

    // 收起条上的"有进程在跑"状态点：由 TerminalHost 每秒回调一次（内部读 engine.isAlive）。

    if (active != null && active.kind == TerminalTabKind.Agent) {
        val processId = active.id.removePrefix("agent-")
        MirrorTerminalView(
            title = active.title,
            text = state.mirrors[processId].orEmpty(),
            modifier = modifier,
        )
    } else {
        // "引用选中到输入框"的按钮放在收起条上（见 TerminalPanel），这里只提供内容。
        TerminalHost(
            tabs = userTabs,
            activeTabId = active?.id,
            settings = settings,
            onExit = viewModel::onProcessExit,
            modifier = modifier,
            onColumns = viewModel::onColumns,
            onRunningCount = viewModel::setRunning,
        )
    }
}
