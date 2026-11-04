package dev.shinglem.hermes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.shinglem.hermes.core.config.AppOptionsHolder
import dev.shinglem.hermes.core.config.ThemeMode
import dev.shinglem.hermes.di.AppContainer
import dev.shinglem.hermes.feature.shell.AppShell
import dev.shinglem.hermes.feature.terminal.TerminalTabVm
import dev.shinglem.hermes.ui.theme.HermesTheme

/**
 * 根 Composable：主题 + 骨架。
 *
 * 主题来自 `config/options.json` 的 `ui.theme`，热生效（[AppOptionsHolder] 由
 * `OptionsWatcher` 推送）—— 所以改文件后界面配色会立即跟着变，无需重启。
 *
 * [terminalContent] 是终端插槽：真正的终端在 desktop 侧的 `:modules:terminal`，
 * 这里只做透传，保证 commonMain 不依赖 Swing/AWT。
 */
@Composable
fun App(
    container: AppContainer,
    onExitRequest: () -> Unit = {},
    terminalContent: @Composable (TerminalTabVm, Modifier) -> Unit = { _, _ -> },
    onQuoteTerminalSelection: (() -> Unit)? = null,
) {
    val options by AppOptionsHolder.state.collectAsState()
    val darkTheme = when (options.ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    HermesTheme(darkTheme = darkTheme, style = options.ui.style) {
        // 后端由 Main（application 作用域）拉起：与窗口是否可见无关。
        AppShell(container, onExitRequest, terminalContent, onQuoteTerminalSelection)
    }
}
