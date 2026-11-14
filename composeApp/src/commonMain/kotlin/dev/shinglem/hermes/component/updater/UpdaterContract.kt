package dev.shinglem.hermes.component.updater

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.BackendUpdateCheckDto
import dev.shinglem.hermes.core.error.AppError

/**
 * 「关于」页的检查更新状态（S5：从 `UpdaterViewModel` 迁移）。
 *
 * **为什么不是 `Loadable<T>`**：这里三态并存 —— "正在查"可以与"上次的结果"同时存在
 * （用户再点一次时旧结果还在，只是按钮转圈）。`Loadable` 是互斥的四态，塞不下。
 *
 * ⚠️ 查的是**后端**（`hermes` 运行时）的更新，不是本客户端自身：
 * `/api/hermes/update/check` 是唯一现成的接口。
 */
@Immutable
data class UpdaterState(
    val checking: Boolean = false,
    val result: BackendUpdateCheckDto? = null,
    val error: AppError? = null,
) {
    val updateAvailable: Boolean get() = result?.updateAvailable == true
}

sealed interface UpdaterIntent {
    /** 检查更新。[force] = true 时绕过后端缓存。 */
    data class Check(val force: Boolean = true) : UpdaterIntent

    /** 请求失败后用户点了关闭（把错误清掉，避免横幅一直挂着）。 */
    data object DismissError : UpdaterIntent
}
/**
 * 兼容别名：UI 层（`SettingsPane` / `DesktopSettingsPages`）还在用旧名字。
 *
 * S5 只迁状态层，UI 的批量改名留到 S8 一起做 —— 现在改会让这一轮的 diff 混进
 * 几十处纯改名，掩盖真正的行为变化。别名让两边都能编译。
 */
typealias UpdateState = UpdaterState