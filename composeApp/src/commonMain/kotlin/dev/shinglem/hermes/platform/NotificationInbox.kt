package dev.shinglem.hermes.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** 通知上的一个动作按钮（点了直接处理，不必先把窗口拉到前台）。 */
data class NotificationAction(val label: String, val onClick: () -> Unit)

/**
 * 一条需要**画出来**的通知（带按钮的那种）。
 *
 * [onBodyClick] 是点标题 / 正文的动作（通常是"跳到发起它的会话"）；
 * [autoDismissMs] 为 0 表示不自动消失（用户必须点按钮或关闭）。
 */
data class NotificationRequest(
    val id: Long,
    val title: String,
    val body: String,
    val actions: List<NotificationAction> = emptyList(),
    val onBodyClick: (() -> Unit)? = null,
    val autoDismissMs: Long = 30_000,
)

/**
 * 带按钮通知的**唯一状态源**（UI 层订阅它渲染窗口，见 desktop 的 `NotificationOverlay`）。
 *
 * 为什么状态放在 common：这种通知必须由 Compose 画（系统气泡不支持按钮），
 * 而"什么时候显示哪一条"是纯状态问题，平台实现只负责投递。
 * 同一时刻只留一条：审批一次只会有一个，堆叠反而挡屏幕。
 */
object NotificationInbox {
    private val counter = MutableStateFlow(0L)

    private val _current = MutableStateFlow<NotificationRequest?>(null)
    val current: StateFlow<NotificationRequest?> = _current.asStateFlow()

    fun post(
        title: String,
        body: String,
        actions: List<NotificationAction>,
        onBodyClick: (() -> Unit)? = null,
        autoDismissMs: Long = 30_000,
    ) {
        _current.value = NotificationRequest(
            id = counter.updateAndGet { it + 1 },
            title = title,
            body = body,
            actions = actions,
            onBodyClick = onBodyClick,
            autoDismissMs = autoDismissMs,
        )
    }

    /** 只关掉这一条：期间可能已经有新的一条顶上来，别把它一起关掉。 */
    fun dismiss(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }

    fun dismissAll() {
        _current.value = null
    }
}
