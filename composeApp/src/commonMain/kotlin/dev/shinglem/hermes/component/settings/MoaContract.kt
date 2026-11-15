package dev.shinglem.hermes.component.settings

import androidx.compose.runtime.Immutable
import dev.shinglem.hermes.core.contract.MoaConfigDto
import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.feature.settings.moaPresetProblem
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * 模型 · MoA（Mixture-of-Agents）页状态。
 *
 * [presets] 是**整份**预设表：后端把提交的命名 map 当权威，少一个就删一个，
 * 所以编辑态必须持有全部预设，不能只保存当前选中的那个。
 *
 * 与迁移前的差别只有容器类型（`Map` → `PersistentMap`）与多了 [profile] ——
 * 后者用来在换 profile 时丢掉未保存的编辑。
 */
@Immutable
data class MoaState(
    val config: Loadable<MoaConfigDto> = Loadable.Idle,
    val presets: PersistentMap<String, MoaPresetDto> = persistentMapOf(),
    val defaultPreset: String = "default",
    /** 后端正在生效的预设；空 = 用 [defaultPreset]。 */
    val activePreset: String = "",
    val selected: String = "",
    val profile: String? = null,
    val dirty: Boolean = false,
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val preset: MoaPresetDto? get() = presets[selected]

    val presetNames: List<String> get() = presets.keys.toList()

    /** 当前预设的问题（空 = 可以保存）。 */
    val presetProblem: String? get() = preset?.let(::moaPresetProblem)

    val saving: Boolean get() = busyKey != null
}

sealed interface MoaIntent {
    /** [connected] 为 false 时是空操作（同 Security 的理由）。 */
    data class Appear(val connected: Boolean = true) : MoaIntent

    data object Reload : MoaIntent

    data class Select(val name: String) : MoaIntent

    data object AddPreset : MoaIntent

    data object DeletePreset : MoaIntent

    data object SetDefault : MoaIntent

    data object SetActive : MoaIntent

    data class SetPreset(val preset: MoaPresetDto) : MoaIntent

    data object AddReference : MoaIntent

    data class RemoveReference(val index: Int) : MoaIntent

    data class UpdateReference(val index: Int, val slot: dev.shinglem.hermes.core.contract.MoaSlotDto) : MoaIntent

    data object Save : MoaIntent

    data object DismissBanner : MoaIntent
}

internal sealed interface MoaResult {
    data class Loading(val stale: MoaConfigDto?) : MoaResult
    data class Loaded(val config: MoaConfigDto, val profile: String?) : MoaResult
    data class Failed(val error: AppError, val stale: MoaConfigDto?) : MoaResult

    /**
     * 预设表的任一编辑（选中 / 新增 / 删除 / 改字段）。
     *
     * 合成**一条** Result 而不是给每个编辑动作一条：它们的 Reducer 形态完全一样
     * （"换掉 presets + 标脏 + 清横幅"），拆开只会让 Reducer 里多六段重复代码。
     * 具体改法（怎么算出新 presets）在 Executor 里算好再交过来 —— 那本来就不是纯函数能做的
     * （要读当前选中项）。
     */
    data class PresetsEdited(
        val presets: PersistentMap<String, MoaPresetDto>,
        val selected: String,
        val defaultPreset: String,
        val activePreset: String,
    ) : MoaResult

    data class Invalid(val message: String) : MoaResult
    data object Saving : MoaResult
    data class Saved(val config: MoaConfigDto) : MoaResult
    data class SaveFailed(val message: String) : MoaResult
    data object BannersCleared : MoaResult

    /**
     * 只切换"当前查看哪个预设"，**不置脏**。
     *
     * 与 [PresetsEdited] 分开是因为 `selected` 不参与保存（`save()` 只发
     * `defaultPreset` / `activePreset` / `presets`），把它当编辑会让用户一进页面切个
     * 预设就看到"有未保存的改动"、保存按钮变亮（迁移前 `select()` 不置脏）。
     */
    data class Selected(val name: String) : MoaResult
}

sealed interface MoaEffect {
    data class Toast(val text: String) : MoaEffect
}

/** `MoaConfigDto.presets` 是普通 Map，转到编辑态用的 PersistentMap。 */
internal fun Map<String, MoaPresetDto>.toEditingPresets(): PersistentMap<String, MoaPresetDto> =
    toPersistentMap()
