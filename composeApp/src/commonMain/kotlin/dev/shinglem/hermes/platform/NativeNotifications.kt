package dev.shinglem.hermes.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * 系统原生通知（Windows toast）的可用状态。
 *
 * 三个字段含义不同，设置页要分开显示：
 * - [requested]：用户在设置里把「使用系统通知」打开了；
 * - [ready]：**真的能用**（native 库初始化成功 + 开始菜单快捷方式存在）；
 * - [shortcutPath]：快捷方式路径（打开开关后由库创建/更新）。
 *
 * [degraded] 为真时说明用户想要原生但用不了（例如快捷方式被删、托盘被策略禁用），
 * 这时通知会**回退**到应用内自绘窗 —— 不能静默变成"没有通知"。
 */
data class NativeNotificationStatus(
    val requested: Boolean = false,
    val ready: Boolean = false,
    val shortcutPath: String? = null,
    val message: String? = null,
) {
    val degraded: Boolean get() = requested && !ready
}

/**
 * 原生通知开关（默认关 = 用应用内自绘通知窗）。
 *
 * 为什么不是默认开：Windows toast 的 AUMID 归属**必须**有开始菜单里的 `.lnk`
 * （实测桌面/其它目录/纯注册表都不行，见 `12-通知窗.md`），绿到发黑的便携包
 * 不该默认往用户开始菜单里塞东西。打开开关才创建。
 */
expect object NativeNotifications {
    val status: StateFlow<NativeNotificationStatus>

    /** 按用户开关初始化 / 反初始化。可重复调用（幂等）。 */
    fun sync(requested: Boolean)
}
