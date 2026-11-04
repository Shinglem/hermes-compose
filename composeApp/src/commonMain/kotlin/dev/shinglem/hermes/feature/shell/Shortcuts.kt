package dev.shinglem.hermes.feature.shell

/**
 * 全局快捷键（`AGENTS.md` §4、`.design/草案/04-ui-spec.md` §6）。
 *
 * 解析放在纯函数里，便于单测 —— 界面层只负责把 `KeyEvent` 拆成
 * `ctrl` / `shift` / `utf16CodePoint` 三个值传进来。
 */
enum class AppShortcut {
    NEW_SESSION,
    PREV_SESSION,
    NEXT_SESSION,
    OPEN_SETTINGS,
    COMMAND_PALETTE,
    TERMINAL_TOGGLE,
    TERMINAL_NEW_TAB,
    TERMINAL_PREV_TAB,
    TERMINAL_NEXT_TAB,
}

/**
 * 按键 → 快捷键动作。
 *
 * `utf16CodePoint` 是**字符**而不是物理键位：`Shift+[` 得到的是 `{`，
 * `Shift+`` 得到的还是 `` ` ``（US 布局）。所以这里按"归一化后的字符"匹配，
 * 同时接受未移位与移位后的两种字符。
 */
fun appShortcutOf(ctrl: Boolean, shift: Boolean, codePoint: Int): AppShortcut? {
    if (!ctrl) return null
    val ch = codePoint.toChar().lowercaseChar()
    return when (ch) {
        'n' -> AppShortcut.NEW_SESSION
        ',' -> AppShortcut.OPEN_SETTINGS
        'k' -> AppShortcut.COMMAND_PALETTE
        '`' -> if (shift) AppShortcut.TERMINAL_NEW_TAB else AppShortcut.TERMINAL_TOGGLE
        '[', '{' -> AppShortcut.PREV_SESSION
        ']', '}' -> AppShortcut.NEXT_SESSION
        '\t' -> if (shift) AppShortcut.TERMINAL_PREV_TAB else AppShortcut.TERMINAL_NEXT_TAB
        else -> null
    }
}

/** 这条快捷键是否只在会话模式有意义（终端与新建会话都要有会话上下文）。 */
fun AppShortcut.requiresSessionMode(): Boolean = when (this) {
    AppShortcut.OPEN_SETTINGS, AppShortcut.COMMAND_PALETTE -> false
    else -> true
}
