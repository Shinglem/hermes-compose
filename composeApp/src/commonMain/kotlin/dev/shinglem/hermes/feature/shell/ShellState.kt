package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.prefs.AppPreferences

/**
 * 顶层导航模式。
 *
 * `ShellState` 本体在 `component/shell/ShellContract.kt`（S8：旧 `feature.shell.ShellState`
 * 已删除，两者字段一致，`toLegacy()` 投影随之消失）。
 */
enum class ShellMode { SESSIONS, CAPABILITIES, SETTINGS }

/** 官方默认档案名（S5：从 `ShellViewModel` 移到顶层常量，ViewModel 已删除）。 */
const val DEFAULT_PROFILE: String = "default"

/** 偏好里的 mode 字符串 → 枚举。 */
fun String.toShellModeForShellComponent(): ShellMode =
    if (this == AppPreferences.MODE_CAPABILITIES) ShellMode.CAPABILITIES else ShellMode.SESSIONS

/**
 * 枚举 → 偏好的 mode 字符串。
 *
 * 只有 `CAPABILITIES` 会被持久化（迁移前的 `openCapabilities()`）；`SETTINGS` 是一次性
 * 去处，重启应回到会话，所以映射回 `MODE_SESSIONS`（见 [toShellModeForShellComponent]）。
 */
fun ShellMode.toPreference(): String = when (this) {
    ShellMode.CAPABILITIES -> AppPreferences.MODE_CAPABILITIES
    ShellMode.SESSIONS, ShellMode.SETTINGS -> AppPreferences.MODE_SESSIONS
}