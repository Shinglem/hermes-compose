package dev.shinglem.hermes.component.notification

import dev.shinglem.hermes.feature.chat.SessionKey
import dev.shinglem.hermes.feature.shell.ReminderGate
import dev.shinglem.hermes.feature.shell.shouldRemindForSession
import dev.shinglem.hermes.platform.NotificationAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 通知的**策展**结果（S5：从 `AppShell` 里那两个局部函数搬出来）。
 *
 * UI 侧只做两件事：把 [target] 记成"点通知要跳去的会话"、调 `SystemNotifications.show`。
 * 决策（该不该弹、正文怎么截断、按钮怎么绑）全在这里 —— 那些条件能单测，
 * 而 Composable 里的局部闭包只能靠手点验证。
 */
sealed interface NotificationEffect {
    /**
     * 弹一条通知。
     *
     * @param dedupeId `ReminderGate` 的去重键（会话被删 / 请求已处理时清掉）。
     * @param target 点通知要跳去的会话；null = 只把窗口拉到前台。
     */
    data class Notify(
        val dedupeId: String,
        val title: String,
        val body: String,
        val target: SessionKey?,
        val actions: List<NotificationAction> = emptyList(),
    ) : NotificationEffect

    /** 把窗口拉到前台（不跳会话）。 */
    data object BringToFront : NotificationEffect
}

/**
 * 通知策展组件（S5）。
 *
 * ### 为什么它要成为 Component
 *
 * 迁移前 `remind` / `remindApproval` 写在 `AppShell` 的 body 里，依赖 preferences、
 * `AppFocus`、`ReminderGate` 与当前选中的会话。结果：**任何条件变化都只能靠手点验证**，
 * 而它们的错误表现是"该弹没弹 / 弹了两遍" —— 用户很难复述，排查成本极高。
 *
 * 搬出来后：判据（`shouldRemindForSession` + `ReminderGate`）是既有纯函数，
 * 组件只负责"按判据决定发不发 Effect"，同样可测；UI 侧退化成"收到就执行"。
 */
interface NotificationComponent {
    fun remindApproval(
        requestId: String,
        target: SessionKey?,
        title: String,
        body: String,
        actions: List<NotificationAction>,
        foreground: Boolean,
        selectedSessionId: String?,
        onScreen: Boolean,
    )

    fun remindTurnFinished(
        dedupeId: String,
        target: SessionKey?,
        title: String,
        body: String,
        foreground: Boolean,
        selectedSessionId: String?,
    )

    /** 会话被删 / 请求已处理：清掉去重记录，避免集合无限增长。 */
    fun forget(dedupeId: String)

    val effects: Flow<NotificationEffect>
}

/**
 * 默认实现。
 *
 * [enabled] / [supported] 由调用方构造时给出（`preferences.notificationsEnabled` /
 * `SystemNotifications.supported`）—— 不持有 preferences，才能纯 JVM 测试。
 */
class DefaultNotificationComponent(
    private val enabled: Boolean,
    private val supported: Boolean,
) : NotificationComponent {

    private val gate = ReminderGate()

    private val _effects = MutableSharedFlow<NotificationEffect>(
        replay = 0,
        extraBufferCapacity = 32,
    )

    override val effects: Flow<NotificationEffect> = _effects

    override fun remindApproval(
        requestId: String,
        target: SessionKey?,
        title: String,
        body: String,
        actions: List<NotificationAction>,
        foreground: Boolean,
        selectedSessionId: String?,
        onScreen: Boolean,
    ) {
        val dedupeId = "approval:$requestId"
        if (!shouldSend(dedupeId, target, foreground, selectedSessionId, onScreen)) return
        _effects.tryEmit(
            NotificationEffect.Notify(
                dedupeId = dedupeId,
                title = title,
                body = body.take(180),
                target = target,
                actions = actions,
            ),
        )
    }

    override fun remindTurnFinished(
        dedupeId: String,
        target: SessionKey?,
        title: String,
        body: String,
        foreground: Boolean,
        selectedSessionId: String?,
    ) {
        if (!shouldSend(dedupeId, target, foreground, selectedSessionId, onScreen = false)) return
        _effects.tryEmit(
            NotificationEffect.Notify(
                dedupeId = dedupeId,
                title = title,
                body = body.take(180),
                target = target,
            ),
        )
    }

    override fun forget(dedupeId: String) {
        gate.forget(dedupeId)
    }

    /** 三条判据，与迁移前 `remind` 逐条对应（顺序也一样：开关 → 去重 → 场景）。 */
    private fun shouldSend(
        dedupeId: String,
        target: SessionKey?,
        foreground: Boolean,
        selectedSessionId: String?,
        onScreen: Boolean,
    ): Boolean {
        if (!enabled || !supported) return false
        if (!gate.shouldRemind(dedupeId)) return false
        return shouldRemindForSession(
            target = target,
            foreground = foreground,
            selectedSessionId = selectedSessionId,
            onScreen = onScreen,
        )
    }
}