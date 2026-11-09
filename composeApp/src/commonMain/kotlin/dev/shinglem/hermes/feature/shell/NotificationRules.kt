package dev.shinglem.hermes.feature.shell

import dev.shinglem.hermes.feature.chat.SessionKey

/**
 * 该不该为这个会话弹系统通知。
 *
 * 判据是「用户**现在正看着**它」，按可靠性从高到低：
 *
 * 1. [onScreen]：这张卡片（审批 / 提问）已经渲染在当前会话里 —— 最直接的证据，
 *    不受 sessionId / profile 是否对得上影响。它是"弹了卡片又弹通知"这类误报的兜底。
 * 2. 窗口在前台 + 选中的就是这个会话。**只比 sessionId**：它全局唯一，再比 profile
 *    只会引入 null / "default" 两种写法的误判（曾经表现成"正在看的会话也弹通知"）。
 *
 * 窗口在后台一律提醒 —— 那时用户确实看不到卡片。
 *
 * 抽成纯函数是因为它原来埋在 `AppShell` 的局部闭包里，改一个条件只能靠手点验证；
 * 现在 [shouldRemindForSession] 的条件表可以逐条断言。
 */
internal fun shouldRemindForSession(
    target: SessionKey?,
    foreground: Boolean,
    selectedSessionId: String?,
    onScreen: Boolean = false,
): Boolean {
    if (!foreground) return true
    if (onScreen) return false
    // 定位不到会话（事件没带 session_id）：宁可提醒也别漏。
    if (target == null) return true
    return target.sessionId != selectedSessionId
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
