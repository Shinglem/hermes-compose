package dev.shinglem.hermes.core.prefs

import com.russhwolf.settings.Settings

/**
 * 本地 UI 偏好。平台实现在 `desktopMain` 注入（当前是 `JsonFileSettings`，落 `config/state.json`）。
 *
 * 注意：
 * - 这里**不存 token**，也不存后端地址 —— 那些在 `config/config.json`
 *   （见 `.design/详细设计/工程与代码/05-打包与配置.md` §3）。
 * - 这里只放"程序自己写、用户不该手改"的运行态。
 */
class AppPreferences(
    private val settings: Settings,
    /** 让后端把挂起的写入立即落盘；退出前由 `AppContainer.close()` 调用。 */
    private val onFlush: () -> Unit = {},
) {

    /** 上次选中的 profile；null 表示 `default`。 */
    var activeProfile: String?
        get() = settings.getStringOrNull(PrefKeys.ACTIVE_PROFILE)
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.ACTIVE_PROFILE)
            else settings.putString(PrefKeys.ACTIVE_PROFILE, value)
        }

    var mode: String
        get() = settings.getString(PrefKeys.UI_MODE, MODE_SESSIONS)
        set(value) {
            settings.putString(PrefKeys.UI_MODE, value)
        }

    /** 终端面板布局（展开态 / 高度 / 标签列表）的原始 JSON；解析失败由调用方回落默认值。 */
    var terminalLayout: String?
        get() = settings.getStringOrNull(PrefKeys.TERMINAL_LAYOUT)
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.TERMINAL_LAYOUT)
            else settings.putString(PrefKeys.TERMINAL_LAYOUT, value)
        }

    /** 终端外观设置（字体 / 字号 / 滚动行数 / 配色）的原始 JSON。 */
    var terminalSettings: String?
        get() = settings.getStringOrNull(PrefKeys.TERMINAL_SETTINGS)
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.TERMINAL_SETTINGS)
            else settings.putString(PrefKeys.TERMINAL_SETTINGS, value)
        }

    /** 设置页中栏的折叠状态（逗号分隔的 section key）。 */
    var settingsCollapsed: String?
        get() = settings.getStringOrNull(PrefKeys.SETTINGS_COLLAPSED)
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.SETTINGS_COLLAPSED)
            else settings.putString(PrefKeys.SETTINGS_COLLAPSED, value)
        }

    /** 置顶的会话 id（本地偏好；后端 `pinned` 需要 mutation fence，暂未接）。 */
    var pinnedSessions: Set<String>
        get() = settings.getStringOrNull(PrefKeys.PINNED_SESSIONS)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
        set(value) {
            val cleaned = value.filter(String::isNotBlank)
            if (cleaned.isEmpty()) settings.remove(PrefKeys.PINNED_SESSIONS)
            else settings.putString(PrefKeys.PINNED_SESSIONS, cleaned.joinToString(","))
        }

    /** 归档过的会话快照（原始 JSON，见 `ArchivedSessionRef`）；由会话域自行序列化。 */
    var archivedSessions: String?
        get() = settings.getStringOrNull(PrefKeys.ARCHIVED_SESSIONS)
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.ARCHIVED_SESSIONS)
            else settings.putString(PrefKeys.ARCHIVED_SESSIONS, value)
        }

    /** 桌面通知开关：只存"关闭"这一侧，缺省为开（首次安装即生效）。 */
    var notificationsEnabled: Boolean
        get() = settings.getStringOrNull(PrefKeys.NOTIFICATIONS) != "off"
        set(value) {
            if (value) settings.remove(PrefKeys.NOTIFICATIONS)
            else settings.putString(PrefKeys.NOTIFICATIONS, "off")
        }

    /**
     * 用系统原生通知（Windows toast）：**缺省关** = 应用内自绘通知窗。
     *
     * 打开后会创建开始菜单快捷方式（Windows 的 AUMID 归属硬要求，见 `12-通知窗.md`），
     * 所以默认不写任何系统状态；只存"打开"这一侧。
     */
    var nativeNotifications: Boolean
        get() = settings.getStringOrNull(PrefKeys.NATIVE_NOTIFICATIONS) == "on"
        set(value) {
            if (value) settings.putString(PrefKeys.NATIVE_NOTIFICATIONS, "on")
            else settings.remove(PrefKeys.NATIVE_NOTIFICATIONS)
        }

    /** 会话草稿：按 stored session id 存，切走再切回来能续上。 */
    fun draft(sessionId: String): String? =
        settings.getStringOrNull(PrefKeys.draft(sessionId))?.takeIf { it.isNotBlank() }

    fun setDraft(sessionId: String, text: String) {
        if (text.isBlank()) clearDraft(sessionId) else settings.putString(PrefKeys.draft(sessionId), text)
    }

    fun clearDraft(sessionId: String) {
        settings.remove(PrefKeys.draft(sessionId))
    }

    /**
     * 上次退出时的窗口几何。null 表示"没有记录"，用 `config.json` 的默认尺寸。
     *
     * 最大化状态不单独存：最大化时记的是**还原后的** bounds，
     * 下次启动先按普通窗口恢复，再由窗口管理器决定是否最大化。
     */
    var windowBounds: WindowBounds?
        get() = WindowBounds.parse(settings.getStringOrNull(PrefKeys.WINDOW_BOUNDS))
        set(value) {
            if (value == null) settings.remove(PrefKeys.WINDOW_BOUNDS)
            else settings.putString(PrefKeys.WINDOW_BOUNDS, value.encode())
        }

    /** 把挂起的写入立即落盘。退出路径必须调，否则最后 200ms 内的改动会丢。 */
    fun flush() {
        onFlush()
    }

    /**
     * 草稿态（还没有 live session）选中的模型/推理强度。
     *
     * 后端 `config.set model` 要求 live session（否则 4001），所以新建会话前的选择先落这里，
     * 建会话时通过 `session.create` 的 `model` / `provider` / `reasoning_effort` 带过去。
     * 也是「上次选过就沿用」的粘性来源。
     */
    var draftProvider: String?
        get() = settings.getStringOrNull(PrefKeys.DRAFT_PROVIDER)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.DRAFT_PROVIDER)
            else settings.putString(PrefKeys.DRAFT_PROVIDER, value)
        }

    var draftModel: String?
        get() = settings.getStringOrNull(PrefKeys.DRAFT_MODEL)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.DRAFT_MODEL)
            else settings.putString(PrefKeys.DRAFT_MODEL, value)
        }

    var draftReasoning: String?
        get() = settings.getStringOrNull(PrefKeys.DRAFT_REASONING)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) settings.remove(PrefKeys.DRAFT_REASONING)
            else settings.putString(PrefKeys.DRAFT_REASONING, value)
        }

    companion object {
        const val MODE_SESSIONS = "sessions"
        const val MODE_CAPABILITIES = "capabilities"
    }
}
