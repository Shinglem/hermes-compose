package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.CurrentModelDto
import dev.shinglem.hermes.core.contract.CustomEndpointDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.settings.EndpointDraft

/**
 * 自定义端点页状态。
 *
 * [draft] 非空表示正在编辑（新建或改某个端点），此时列表让位给表单；
 * 保存或取消后回到列表。这个"单表单 + 列表"的切换保持原样 —— 它是对的
 * （端点字段多，行内编辑放不下）。
 */
@Immutable
data class CustomEndpointState(
    val endpoints: Loadable<List<CustomEndpointDto>> = Loadable.Idle,
    val current: CurrentModelDto = CurrentModelDto(),
    val profile: String? = null,
    val draft: EndpointDraft? = null,
    val validating: Boolean = false,
    /** 最近一次探测的结论（人话）；[probeOk] 为空表示只是警告。 */
    val probe: String? = null,
    val probeOk: Boolean? = null,
    /** 待确认删除的端点 id。 */
    val pendingDelete: String? = null,
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val busy: Boolean get() = busyKey != null
}

sealed interface CustomEndpointIntent {
    /** [connected] 为 false 时是空操作（同 Security 的理由）。 */
    data class Appear(val connected: Boolean = true) : CustomEndpointIntent

    data object Reload : CustomEndpointIntent

    data object StartCreate : CustomEndpointIntent

    data class StartEdit(val endpoint: CustomEndpointDto) : CustomEndpointIntent

    data class EditDraft(val draft: EndpointDraft) : CustomEndpointIntent

    data object CancelEdit : CustomEndpointIntent

    data object Validate : CustomEndpointIntent

    data object Save : CustomEndpointIntent

    data class Activate(val endpointId: String) : CustomEndpointIntent

    data class AskDelete(val endpointId: String) : CustomEndpointIntent

    data object DismissDelete : CustomEndpointIntent

    data object ConfirmDelete : CustomEndpointIntent

    data object DismissBanner : CustomEndpointIntent
}

internal sealed interface CustomEndpointResult {
    data class Loading(val stale: List<CustomEndpointDto>?) : CustomEndpointResult
    data class Loaded(
        val endpoints: List<CustomEndpointDto>,
        val current: CurrentModelDto,
        val profile: String?,
    ) : CustomEndpointResult
    data class Failed(val error: AppError, val stale: List<CustomEndpointDto>?) : CustomEndpointResult
    data class DraftOpened(val draft: EndpointDraft) : CustomEndpointResult
    data class DraftChanged(val draft: EndpointDraft) : CustomEndpointResult
    data object DraftClosed : CustomEndpointResult
    data object Validating : CustomEndpointResult
    data class Probed(val message: String, val ok: Boolean, val resolvedDraft: EndpointDraft?) :
        CustomEndpointResult
    data class ProbeFailed(val message: String) : CustomEndpointResult
    data class Saving(val key: String) : CustomEndpointResult
    data class Saved(
        val endpoints: List<CustomEndpointDto>,
        val current: CurrentModelDto,
        val notice: String,
    ) : CustomEndpointResult
    data class BusyFailed(val message: String) : CustomEndpointResult
    data class NoticeOnly(val message: String) : CustomEndpointResult
    data class AskedDelete(val endpointId: String) : CustomEndpointResult
    data object DeleteDismissed : CustomEndpointResult
    data object BannersCleared : CustomEndpointResult
}

sealed interface CustomEndpointEffect {
    data class Toast(val text: String) : CustomEndpointEffect
}

/**
 * 探测后把 base_url 换成真正供出 `/models` 的那个地址。
 *
 * 后端保存时也是这么改的（运行时会在保存的 URL 后直接拼 `/chat/completions`），
 * 表单里先同步过来，用户看到的就是最终会落盘的地址。
 *
 * 从原 `CustomEndpointState.kt` 平移过来 —— 它被 `CustomEndpointExecutor` 与
 * UI 共用，所以放在 Contract 这一层（不依赖 Compose，也不依赖 Store）。
 */
fun resolveProbeUrl(draft: EndpointDraft?, resolved: String?): EndpointDraft? {
    val target = resolved?.trim()?.trimEnd('/').orEmpty()
    return when {
        draft == null || target.isBlank() -> draft
        draft.baseUrl.trim().trimEnd('/') == target -> draft
        else -> draft.copy(baseUrl = target)
    }
}
