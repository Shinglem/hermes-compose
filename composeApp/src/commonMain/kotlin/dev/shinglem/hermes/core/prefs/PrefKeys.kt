package dev.shinglem.hermes.core.prefs

/** 偏好键常量：只在这里出现字符串，避免各处手写 key。 */
object PrefKeys {
    const val ACTIVE_PROFILE = "ui.activeProfile"
    const val UI_MODE = "ui.mode"
    const val COLLAPSED_GROUPS = "groups.collapsed"

    /** 上次退出时的窗口位置与大小，格式 `x,y,w,h`（见 `WindowBounds`）。 */
    const val WINDOW_BOUNDS = "ui.windowBounds"

    /** 草稿态（还没有会话时）选中的模型：provider / model / 推理强度。 */
    const val DRAFT_PROVIDER = "model.draftProvider"
    const val DRAFT_MODEL = "model.draftModel"
    const val DRAFT_REASONING = "model.draftReasoning"

    /** 终端面板：展开态、高度比例、标签列表（JSON，见 `TerminalLayout`）。 */
    const val TERMINAL_LAYOUT = "ui.terminalLayout"

    /** 终端外观设置（JSON，见 `TerminalSettings`）。 */
    const val TERMINAL_SETTINGS = "ui.terminalSettings"

    /** 设置页折叠状态（JSON 数组或逗号分隔的分组 key）。 */
    const val SETTINGS_COLLAPSED = "ui.settingsCollapsed"

    /** 会话草稿：`drafts.<sessionId>`。 */
    fun draft(sessionId: String): String = "drafts.$sessionId"

    /** 归档过的会话快照（JSON 数组）：后端列表不返回归档行，只能靠自己记住才能给"恢复"入口。 */
    const val ARCHIVED_SESSIONS = "sessions.archived"

    /**
     * 置顶的会话 id（逗号分隔）。
     *
     * 后端虽然有 `pinned` 字段，但写它要过 mutation fence（`request_id` + `expected_revision`
     * + `expected_generation` 的一致快照），当前 REST PATCH 只回 200 不落库 —— 先用本地偏好，
     * 等官方桌面端那套 fence 协议吃透再切后端。
     */
    const val PINNED_SESSIONS = "sessions.pinned"

    /** 桌面通知开关（只存"关闭"这一侧，缺省 = 开）。 */
    const val NOTIFICATIONS = "ui.notifications"
    /** 用系统原生通知（Windows toast）：缺省关 = 应用内自绘窗。 */
    const val NATIVE_NOTIFICATIONS = "ui.nativeNotifications"
}
