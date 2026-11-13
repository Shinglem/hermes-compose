package dev.shinglem.hermes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.shinglem.hermes.core.config.AppOptions
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
    HermesTheme(darkTheme = options.darkTheme(), style = options.ui.style) {
        AppContent(container, onExitRequest, terminalContent, onQuoteTerminalSelection)
    }
}

/**
 * 与 [App] 同内容但**不含**主题：由调用方自己套 `HermesTheme`。
 *
 * 桌面壳要在同一个主题里画标题栏（Nucleus 的 `TitleBar` 不自带配色），所以 `Main.kt`
 * 先开 `HermesTheme` 再放「标题栏 + [AppContent]」；如果这里再套一层，标题栏就落到外面
 * 那层的默认 M3 亮色主题里，出现深灰底 + 深色文字这种看不清的组合（S6 首次实跑即如此）。
 */
@Composable
fun AppContent(
    container: AppContainer,
    onExitRequest: () -> Unit = {},
    terminalContent: @Composable (TerminalTabVm, Modifier) -> Unit = { _, _ -> },
    onQuoteTerminalSelection: (() -> Unit)? = null,
) {
    // 后端由 Main（application 作用域）拉起：与窗口是否可见无关。
    AppShell(container, onExitRequest, terminalContent, onQuoteTerminalSelection)
}

/** `ui.theme` → 是否深色。`system` 交给 Compose 读系统外观。 */
@Composable
fun AppOptions.darkTheme(): Boolean = when (ui.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
