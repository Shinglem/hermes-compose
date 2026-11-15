package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.core.app.ProductInfo
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.feature.chat.PendingApproval
import dev.shinglem.hermes.feature.chat.SessionKey
import dev.shinglem.hermes.platform.AppFocus
import dev.shinglem.hermes.platform.NotificationAction
import dev.shinglem.hermes.platform.SystemNotifications

/**
 * `AppShell` 里的通知发出逻辑（原 `remind` / `remindApproval` 两个局部函数）。
 *
 * 之所以搬出 Composable：这两个函数有 4 个判据（开关 / 去重 / 场景 / 是否已在屏上）
 * 与 3 条日志路径，写在 `remember` 闭包里只能靠手点验证。搬成普通类后
 * [dev.shinglem.hermes.component.notification.NotificationComponent] 的判据可以复用，
 * 这里只负责"该弹时怎么弹"（标题、正文截断、按钮绑定、点击跳转）。
 *
 * 状态全部由构造参数注入，类本身不持有可变字段 —— 所以它在重组时不参与记忆，
 * 每帧重建也不会丢 [gate]（去重记录由调用方 `remember` 持有）。
 */
internal class ShellNotifier(
    /**
     * 通知总开关。**调用时读**，不能用组合期快照 —— 这个对象每帧重建，但持有它的
     * 两个 `LaunchedEffect` 不会因偏好变化而重启；捕获成字段会让"运行中切换开关"失效
     * （关掉后审批仍弹、打开后本次会话仍不弹，要重启才生效）。迁移前是在 `remind()`
     * 内部实时读 `container.preferences.notificationsEnabled`。
     */
    private val enabled: () -> Boolean,
    private val supported: Boolean,
    private val gate: ReminderGate,
    /** 当前选中的会话 id（**调用时**读，不能用组合期快照）。 */
    private val selectedSessionId: () -> String?,
    /** 点通知后的跳转（由调用方负责协程与跨 profile 缓冲）。 */
    private val onJump: (SessionKey) -> Unit,
    /** 审批按钮：`(requestId, choice)`。 */
    private val onResolveApproval: (String, String) -> Unit,
) {

    fun remind(
        id: String,
        target: SessionKey?,
        title: String,
        body: String,
        actions: List<NotificationAction> = emptyList(),
        foreground: Boolean = AppFocus.isForeground,
        /** 这张卡片是不是已经渲染在用户眼前的会话里（判据见 [shouldRemindForSession]）。 */
        onScreen: Boolean = false,
    ) {
        val enabledNow = enabled()
        if (!enabledNow || !supported) {
            Log.info("remind skipped id=$id (enabled=$enabledNow supported=$supported)")
            return
        }
        val selected = selectedSessionId()
        val remind = shouldRemindForSession(
            target = target,
            foreground = foreground,
            selectedSessionId = selected,
            onScreen = onScreen,
        )
        if (!remind) {
            Log.info("remind skipped id=$id (foreground=$foreground onScreen=$onScreen target=${target?.sessionId} selected=$selected)")
            return
        }
        if (!gate.shouldRemind(id)) {
            Log.info("remind skipped id=$id (already reminded)")
            return
        }
        Log.info("remind show id=$id actions=${actions.size} foreground=$foreground onScreen=$onScreen target=${target?.sessionId} selected=$selected")
        SystemNotifications.setPendingAction(target?.let { key -> { onJump(key) } })
        SystemNotifications.show(title, body.take(180), actions)
    }

    /** 审批提醒：带上「允许一次 / 拒绝」按钮，通知里就能就地处理（不必先切窗口）。 */
    fun remindApproval(
        approval: PendingApproval,
        target: SessionKey?,
        foreground: Boolean = AppFocus.isForeground,
        /** 卡片已经在界面上（当前会话的审批）——此时再弹系统通知就是纯噪音。 */
        onScreen: Boolean,
    ) {
        remind(
            id = "approval:${approval.requestId}",
            target = target,
            title = "${ProductInfo.DISPLAY_NAME} · 等待确认",
            body = approval.command.trim()
                .ifBlank { approval.description.trim() }
                .ifBlank { "有一个命令等待确认" },
            actions = listOf(
                NotificationAction("允许一次") { onResolveApproval(approval.requestId, "once") },
                NotificationAction("拒绝") { onResolveApproval(approval.requestId, "deny") },
            ),
            foreground = foreground,
            onScreen = onScreen,
        )
    }
}
