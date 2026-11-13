package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.ConfigFieldSpec
import dev.shinglem.hermes.feature.settings.SecurityTab
import dev.shinglem.hermes.feature.settings.readPath
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonObject

/**
 * 安全三页（审批 / 隐私与网络 / 检查点）的 UI 状态。
 *
 * 与迁移前的 `SecurityState` 差异（S2 模板的一部分）：
 * - `edits` 用 `PersistentMap`：它是每次按键都更新的高频结构，
 *   `Map + copy` 会每帧复制整个 map；`@Immutable` 让 Compose 能跳过未变分支。
 * - `busyKey` / `notice` / `error` 保持不变 —— 这三者是**可见状态**，不是一次性事件；
 *   做成 Label 会让"正在保存…"在重进页面时丢失。
 */
@Immutable
data class SecurityState(
    val tab: SecurityTab = SecurityTab.APPROVALS,
    val config: Loadable<JsonObject> = Loadable.Idle,
    /** 当前配置属于哪个 profile：换 profile 时要丢掉上一份 edits。 */
    val profile: String? = null,
    /** 用户改过的字段（点分路径 → 文本）；保存成功后清空。 */
    val edits: PersistentMap<String, String> = persistentMapOf(),
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    /** 表单显示值：改过的用编辑值，否则读后端配置。 */
    fun valueOf(spec: ConfigFieldSpec): String =
        edits[spec.path] ?: config.valueOrNull?.readPath(spec.path).orEmpty()

    val dirty: Boolean get() = edits.isNotEmpty()

    val saving: Boolean get() = busyKey != null
}

sealed interface SecurityIntent {
    /**
     * 页面可见 / profile 变化：只在没读过时拉一次。
     *
     * [connected] 是**后端是否已就绪**。迁移前这一步由 UI 的
     * `LaunchedEffect(profile, isConnected, mode)` 把关（"只有连上后端才拉，
     * local 后端 ready 之前一定失败"）。Component 接管生命周期后不能再依赖 UI，
     * 所以由调用方把就绪信号作为 Intent 的参数传进来 —— 未就绪时**什么都不做**，
     * 等下一次 `Appear`（已连接）再读。
     */
    data class Appear(val connected: Boolean = true) : SecurityIntent

    /** 用户点「重新读取」。 */
    data object Reload : SecurityIntent

    /** 表单字段改名。 */
    data class Edit(val path: String, val text: String) : SecurityIntent

    /** 保存改动过的字段（只提交稀疏 patch）。 */
    data class Save(val specs: List<ConfigFieldSpec>) : SecurityIntent

    data object DismissBanner : SecurityIntent
}

/** Executor 内部结果，不对 UI 暴露。 */
internal sealed interface SecurityResult {
    data class Loading(val stale: JsonObject?) : SecurityResult

    /** [profile] 随结果带回：Reducer 不接受外部入参，profile 只能跟着 Result 走。 */
    data class Loaded(val config: JsonObject, val profile: String?) : SecurityResult

    data class Failed(val error: AppError, val stale: JsonObject?) : SecurityResult

    /** 用户改了某个字段。 */
    data class Edited(val path: String, val text: String) : SecurityResult

    /** 开始保存：置忙、清横幅。 */
    data object Saving : SecurityResult

    /** 保存成功并回读完成：清空 edits 并换成新配置。 */
    data class EditsCleared(val config: JsonObject) : SecurityResult

    data class SaveFailed(val error: AppError) : SecurityResult

    data object NothingToSave : SecurityResult

    data object BannersCleared : SecurityResult
}

/** 一次性事件（不进 State）。 */
sealed interface SecurityEffect {
    data class Toast(val text: String) : SecurityEffect
}
