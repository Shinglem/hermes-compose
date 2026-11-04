package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.feature.chat.SessionKey

/**
 * 该不该为这个会话弹系统通知。
 *
 * 判据是「用户**现在正看着**它」：窗口在前台 + 同一个 profile + 选中的就是这个会话。
 * 三条都满足才算看着（不打扰），否则提醒 —— 包括「窗口在前台但人在别的会话 / 别的 profile」，
 * 这正是 `待修复问题.md` 第 2 条踩过的场景。
 *
 * 抽成纯函数是因为它原来埋在 `AppShell` 的局部闭包里，改一个条件只能靠手点验证；
 * 现在 [shouldRemindForSession] 的条件表可以逐条断言。
 */
internal fun shouldRemindForSession(
    target: SessionKey?,
    foreground: Boolean,
    activeProfile: String?,
    selectedSessionId: String?,
): Boolean {
    if (!foreground) return true
    // 定位不到会话（老 payload 没有 session_id）：宁可提醒也别漏。
    if (target == null) return true
    val viewing = target.profile == activeProfile && target.sessionId == selectedSessionId
    return !viewing
}

/**
 * 同一个请求只提醒一次：审批 / 提问的重连补拉、快照恢复都会把同一条再送一遍。
 *
 * 返回 true 表示这次该提醒（首次见到这个 id）。
 */
internal class ReminderGate {
    private val reminded = mutableSetOf<String>()

    fun shouldRemind(id: String): Boolean = reminded.add(id)

    /** 会话被删或请求已处理时清掉，避免集合无限增长。 */
    fun forget(id: String) {
        reminded.remove(id)
    }
}
