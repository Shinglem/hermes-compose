package dev.shinglem.hermes.component.shell

import dev.shinglem.hermes.core.prefs.AppPreferences
import dev.shinglem.hermes.feature.shell.ShellMode
import dev.shinglem.hermes.feature.shell.ShellState as LegacyShellState

/**
 * 新旧 `ShellState` 的过渡桥（S5）。
 *
 * **为什么需要**：`AppShell` 里有 200+ 处 `shellState.xxx`，6 个 settings Component 的
 * `profile` 参数也来自它。这一轮的目标是"把状态层换成 Component"，不是"顺手改 200 处 UI"。
 * 用一个投影把新 state 映射回旧形状，行为完全等价，UI 一行不用动。
 *
 * S8 收尾时统一改成新类型并删掉这个文件。
 */
fun ShellState.toLegacy(): LegacyShellState = LegacyShellState(
    mode = mode,
    profiles = profiles,
    activeProfile = activeProfile,
    connection = connection,
    backendKind = backendKind,
    backend = backend,
    backendLogs = backendLogs,
    gatewayProbe = gatewayProbe,
)

/** 偏好里的 mode 字符串 → 枚举（旧实现是文件内 private 扩展，这里补一个可用的）。 */
fun String.toShellModeForShellComponent(): ShellMode =
    if (this == AppPreferences.MODE_CAPABILITIES) ShellMode.CAPABILITIES else ShellMode.SESSIONS
