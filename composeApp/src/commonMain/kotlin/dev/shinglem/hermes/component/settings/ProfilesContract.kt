package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.ProfileAdminDto
import dev.shinglem.hermes.core.contract.ProfileSoulDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.DEFAULT_PROFILE
import dev.shinglem.hermes.feature.settings.isProfileNameValid
import dev.shinglem.hermes.feature.settings.slugifyProfileName

/** 新建档案对话框的草稿。 */
@Immutable
data class ProfileCreateDraft(
    val name: String = "",
    /** null = 不克隆；`"default"` = 克隆默认档案（后端语义）。 */
    val cloneFrom: String? = DEFAULT_PROFILE,
    val soul: String = "",
) {
    val nameValid: Boolean get() = isProfileNameValid(name)
    val canSubmit: Boolean get() = nameValid
}

/**
 * 配置档案页状态。
 *
 * 写操作一律「提交 → 回读列表」：后端会派生一堆字段（`has_env` / `skill_count` /
 * `gateway_running`），回执里没有 —— 这是原实现就有的纪律，保留。
 */
@Immutable
data class ProfilesState(
    val list: Loadable<List<ProfileAdminDto>> = Loadable.Idle,
    val selected: String? = null,
    val soul: Loadable<ProfileSoulDto> = Loadable.Idle,
    /** SOUL.md 编辑器里的文本（与后端读到的内容不同即为脏）。 */
    val soulDraft: String = "",
    val draft: ProfileCreateDraft? = null,
    /** 正在进行的写操作（按钮禁用 + 转圈）：`"delete:<name>"` / `"soul"` / … */
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val profiles: List<ProfileAdminDto> get() = list.valueOrNull.orEmpty()

    val current: ProfileAdminDto? get() = profiles.firstOrNull { it.name == selected }

    val soulDirty: Boolean
        get() = soul.valueOrNull?.let { soulDraft != it.content } == true

    val busy: Boolean get() = busyKey != null
}

sealed interface ProfilesIntent {
    /** [connected] 为 false 时是空操作（同 Security 的理由）。 */
    data class Appear(val connected: Boolean = true) : ProfilesIntent

    data object Reload : ProfilesIntent

    data class Select(val name: String) : ProfilesIntent

    data class EditSoul(val text: String) : ProfilesIntent

    data object SaveSoul : ProfilesIntent

    data object StartCreate : ProfilesIntent

    data object CancelCreate : ProfilesIntent

    data class EditDraftName(val raw: String) : ProfilesIntent

    data class EditDraftClone(val source: String?) : ProfilesIntent

    data class EditDraftSoul(val text: String) : ProfilesIntent

    data object SubmitCreate : ProfilesIntent

    data class Delete(val name: String) : ProfilesIntent

    data class Rename(val name: String, val newName: String) : ProfilesIntent

    data class Import(val path: String) : ProfilesIntent

    data class Export(val name: String, val output: String) : ProfilesIntent

    data object DismissBanner : ProfilesIntent
}

internal sealed interface ProfilesResult {
    data class Loading(val stale: List<ProfileAdminDto>?) : ProfilesResult

    /**
     * 列表到手。
     *
     * [preferred] 是"尽力选中的那个"（新建/重命名后希望停在它上面）；
     * 为 null 时按"保留原选中 → 默认档 → 第一个"回落。
     */
    data class Loaded(val profiles: List<ProfileAdminDto>, val preferred: String? = null) : ProfilesResult
    data class Failed(val error: AppError, val stale: List<ProfileAdminDto>?) : ProfilesResult

    data class SoulLoading(val name: String) : ProfilesResult
    data class SoulLoaded(val name: String, val soul: ProfileSoulDto) : ProfilesResult
    data class SoulFailed(val name: String, val error: AppError) : ProfilesResult

    data class Selected(val name: String) : ProfilesResult
    data class SoulEdited(val text: String) : ProfilesResult

    data class DraftOpened(val draft: ProfileCreateDraft) : ProfilesResult
    data object DraftClosed : ProfilesResult
    data class DraftChanged(val draft: ProfileCreateDraft) : ProfilesResult

    data class Busy(val key: String) : ProfilesResult
    data class Succeeded(val notice: String) : ProfilesResult
    data class SucceededWithSelection(val notice: String, val selected: String?) : ProfilesResult
    data class FailedWithMessage(val message: String) : ProfilesResult

    /** 删除/重命名后要清掉当前 SOUL 视图（旧档案已经不存在了）。 */
    data class SelectedReset(val selected: String?) : ProfilesResult

    data object BannersCleared : ProfilesResult
}

sealed interface ProfilesEffect {
    data class Toast(val text: String) : ProfilesEffect
}
