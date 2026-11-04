package dev.shinglem.hermes.core.app

/**
 * 开机自启的当前状态（给设置页显示用）。
 *
 * 分三个维度而不是一个布尔：用户需要知道"**配置说要不要**""**系统里有没有**"
 * 以及"**有没有落地**"—— 三者不一致时（典型：配置 `task` 但当前没提权）必须能解释清楚。
 */
data class AutostartInfo(
    /** 配置里是否要求自启（`app.autostart = task`）。 */
    val configured: Boolean,
    /** 计算出来的任务名（`HermesCompose-<home hash>`），显示出来便于用户自己去任务计划程序里找。 */
    val taskName: String,
    /** 系统里是否已有这个任务。 */
    val installed: Boolean,
    /** 配置意图还没落地（缺管理员权限，或写入失败）。 */
    val pending: Boolean,
    /** 当前进程是不是管理员 —— 落地需要它。 */
    val elevated: Boolean,
) {
    companion object {
        val DISABLED = AutostartInfo(
            configured = false,
            taskName = "",
            installed = false,
            pending = false,
            elevated = false,
        )
    }
}
