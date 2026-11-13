package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.AuxModelsDto
import dev.shinglem.hermes.core.contract.AuxTaskDto
import dev.shinglem.hermes.feature.model.ModelCatalog
import dev.shinglem.hermes.feature.model.ModelEntry
import dev.shinglem.hermes.core.contract.ModelInfoAdminDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.FallbackEntry

/** 贵模型二次确认的待办：确认后用同一组参数带 `confirm_expensive_model` 重发。 */
@Immutable
data class PendingModelConfirm(
    val scope: String,
    val provider: String,
    val model: String,
    val task: String?,
    val message: String,
)

/**
 * 模型配置页状态（主模型 / 辅助模型 / 备用模型三块共用一份）。
 *
 * 三块放在同一个 Store 里而不是拆三个：它们**共享同一个"当前选择"**
 * （`provider` / `model`）—— 辅助模型与备用模型的"钉一个"动作都取这个选择。
 * 拆开就得在三个 Store 之间同步，那是自找麻烦。
 */
@Immutable
data class ModelSettingsState(
    val info: Loadable<ModelInfoAdminDto> = Loadable.Idle,
    val catalog: Loadable<ModelCatalog> = Loadable.Idle,
    /** 主模型页里待应用的选择（空 = 未改动）。 */
    val provider: String = "",
    val model: String = "",
    /** 上下文覆盖输入框（空串 = 自动）。 */
    val contextDraft: String = "",
    val pending: PendingModelConfirm? = null,
    val aux: Loadable<AuxModelsDto> = Loadable.Idle,
    /** 备用模型链（主模型不可用时的回退顺序）。 */
    val fallback: Loadable<List<FallbackEntry>> = Loadable.Idle,
    /** 磁盘上 `fallback_providers` 原本是不是数组形状（写回要原样保持）。 */
    val fallbackKeepArray: Boolean = false,
    val profile: String? = null,
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val busy: Boolean get() = busyKey != null

    /** 目录中当前 provider 的模型列表。 */
    fun modelsOf(provider: String): List<ModelEntry> =
        catalog.valueOrNull?.groups?.firstOrNull { it.slug == provider }?.models.orEmpty()

    val providerOptions: List<Pair<String, String>>
        get() = catalog.valueOrNull?.groups
            ?.filter { it.models.isNotEmpty() }
            ?.map { it.slug to it.name }
            .orEmpty()

    val selectionDirty: Boolean
        get() {
            val current = info.valueOrNull ?: return false
            return (provider.isNotBlank() && provider != current.provider) ||
                (model.isNotBlank() && model != current.model)
        }

    /** 当前生效的 provider/model（选择为空时回落到后端值）。 */
    val effectiveProvider: String get() = provider.ifBlank { info.valueOrNull?.provider.orEmpty() }

    val effectiveModel: String get() = model.ifBlank { info.valueOrNull?.model.orEmpty() }
}

sealed interface ModelSettingsIntent {
    /** 进页面：一次性拉 info + 目录 + 辅助模型 + 备用模型。 */
    data class Appear(val connected: Boolean = true) : ModelSettingsIntent

    data object ReloadInfo : ModelSettingsIntent

    data object ReloadCatalog : ModelSettingsIntent

    data object ReloadAux : ModelSettingsIntent

    data object ReloadFallback : ModelSettingsIntent

    data class SelectProvider(val provider: String) : ModelSettingsIntent

    data class SelectModel(val model: String) : ModelSettingsIntent

    data class EditContext(val text: String) : ModelSettingsIntent

    data object ApplyMain : ModelSettingsIntent

    data object ConfirmPending : ModelSettingsIntent

    data object DismissPending : ModelSettingsIntent

    data object SaveContextLength : ModelSettingsIntent

    data class AssignAuxTask(val task: AuxTaskDto) : ModelSettingsIntent

    data object ResetAux : ModelSettingsIntent

    data object AddFallback : ModelSettingsIntent

    data class RemoveFallback(val index: Int) : ModelSettingsIntent

    data object SaveFallback : ModelSettingsIntent

    data object DismissBanner : ModelSettingsIntent
}

internal sealed interface ModelSettingsResult {
    data class InfoLoading(val stale: ModelInfoAdminDto?) : ModelSettingsResult
    data class InfoLoaded(val info: ModelInfoAdminDto, val profile: String?) : ModelSettingsResult
    data class InfoFailed(val error: AppError, val stale: ModelInfoAdminDto?) : ModelSettingsResult

    data class CatalogLoading(val stale: ModelCatalog?) : ModelSettingsResult
    data class CatalogLoaded(val catalog: ModelCatalog) : ModelSettingsResult
    data class CatalogFailed(val error: AppError, val stale: ModelCatalog?) : ModelSettingsResult

    data class AuxLoading(val stale: AuxModelsDto?) : ModelSettingsResult
    data class AuxLoaded(val aux: AuxModelsDto) : ModelSettingsResult
    data class AuxFailed(val error: AppError, val stale: AuxModelsDto?) : ModelSettingsResult

    data class FallbackLoading(val stale: List<FallbackEntry>?) : ModelSettingsResult
    data class FallbackLoaded(val entries: List<FallbackEntry>, val keepArray: Boolean) :
        ModelSettingsResult
    data class FallbackFailed(val error: AppError, val stale: List<FallbackEntry>?) :
        ModelSettingsResult
    data class FallbackEdited(val entries: List<FallbackEntry>) : ModelSettingsResult

    data class SelectionChanged(val provider: String, val model: String) : ModelSettingsResult
    data class ContextEdited(val text: String) : ModelSettingsResult

    data class Busy(val key: String) : ModelSettingsResult
    data class Confirmed(val pending: PendingModelConfirm) : ModelSettingsResult
    data object PendingDismissed : ModelSettingsResult
    data class Succeeded(val notice: String) : ModelSettingsResult
    data class Failed(val message: String) : ModelSettingsResult
    data class Invalid(val message: String) : ModelSettingsResult
    data class Quiet(val notice: String) : ModelSettingsResult
    data object BannersCleared : ModelSettingsResult
}

sealed interface ModelSettingsEffect {
    data class Toast(val text: String) : ModelSettingsEffect
}
