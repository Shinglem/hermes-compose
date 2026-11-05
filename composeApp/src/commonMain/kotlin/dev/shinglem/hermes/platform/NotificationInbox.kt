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
 *
 * **队列而不是单槽**：同一会话里多个审批可能同时到达（`onApprovalRequested` 每来一条就投递一次），
 * 单槽会让后一条直接顶掉前一条 —— 前一条既没被看见、`ReminderGate` 又已经记过 id，
 * 于是永远不再提醒。现在按投递顺序排队，[active] 里最多 [MAX_VISIBLE] 条同屏，
 * 剩下的排队补位；每条各自独立自动收起、独立关闭。
 */
object NotificationInbox {
    /**
     * 同屏最多几条。
     *
     * 3 条是"都能看见"与"不糊住右下角"的折中：审批卡片高 152dp，加上阴影留白约 176dp，
     * 3 条 ≈ 528dp，1080p 屏幕上仍留得下正文区域。
     */
    const val MAX_VISIBLE: Int = 3

    private val counter = MutableStateFlow(0L)

    /** 全部待处理通知，顺序 = 投递顺序。 */
    private val _queue = MutableStateFlow<List<NotificationRequest>>(emptyList())
    val queue: StateFlow<List<NotificationRequest>> = _queue.asStateFlow()

    /** 当前应画出来的那几条（最多 [MAX_VISIBLE]，顺序 = 投递顺序）。 */
    private val _active = MutableStateFlow<List<NotificationRequest>>(emptyList())
    val active: StateFlow<List<NotificationRequest>> = _active.asStateFlow()

    /**
     * 投递一条通知。
     *
     * @return 新通知的 id（测试与日志用）。
     */
    fun post(
        title: String,
        body: String,
        actions: List<NotificationAction>,
        onBodyClick: (() -> Unit)? = null,
        autoDismissMs: Long = 30_000,
    ): Long {
        val request = NotificationRequest(
            id = counter.updateAndGet { it + 1 },
            title = title,
            body = body,
            actions = actions,
            onBodyClick = onBodyClick,
            autoDismissMs = autoDismissMs,
        )
        post(request)
        return request.id
    }

    /**
     * 投递一条已经构造好的通知；同一个 [NotificationRequest.id] 重复投递会被忽略
     * （"同一请求只提醒一次"的语义在投递层再兜一次）。
     */
    fun post(request: NotificationRequest) {
        if (_queue.value.any { it.id == request.id }) return
        publish(_queue.value + request)
    }

    /** 只关掉这一条：其它通知（包括排队中的）不受影响，排队的会自动补位。 */
    fun dismiss(id: Long) {
        val next = _queue.value.filterNot { it.id == id }
        if (next.size != _queue.value.size) publish(next)
    }

    fun dismissAll() {
        if (_queue.value.isNotEmpty()) publish(emptyList())
    }

    private fun publish(next: List<NotificationRequest>) {
        _queue.value = next
        val visible = next.take(MAX_VISIBLE)
        // 只在真的变化时写：值没变就不必让订阅者重排窗口。
        if (_active.value != visible) _active.value = visible
    }
}
