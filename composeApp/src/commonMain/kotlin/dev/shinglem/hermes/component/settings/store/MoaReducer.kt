package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.core.store.Reducer
import dev.shinglem.hermes.component.settings.MoaResult
import dev.shinglem.hermes.component.settings.MoaState
import dev.shinglem.hermes.component.settings.toEditingPresets
import dev.shinglem.hermes.core.result.Loadable
import kotlinx.collections.immutable.persistentMapOf

/** MoA 页的纯状态迁移。 */
internal class MoaReducer : Reducer<MoaState, MoaResult> {

    override fun MoaState.reduce(msg: MoaResult): MoaState = when (msg) {
        is MoaResult.Loading -> if (msg.stale == null) {
            copy(config = Loadable.Loading, error = null)
        } else {
            copy(error = null)
        }

        is MoaResult.Loaded -> {
            val presets = msg.config.presets.toEditingPresets()
            // 选中的预设要"尽力保住"：先认当前选中的，再落到 active → default → 第一个。
            val selected = selected.takeIf { it in presets }
                ?: msg.config.activePreset.takeIf { it in presets }
                ?: msg.config.defaultPreset.takeIf { it in presets }
                ?: presets.keys.firstOrNull().orEmpty()
            // 换 profile 时同样要清掉未保存的编辑（否则会写进另一个仓库）。
            val switched = msg.profile != profile
            copy(
                config = Loadable.Ready(msg.config),
                presets = presets,
                defaultPreset = msg.config.defaultPreset,
                activePreset = msg.config.activePreset,
                selected = selected,
                profile = msg.profile,
                dirty = if (switched) false else dirty,
                busyKey = null,
                error = null,
                notice = if (switched) null else notice,
            )
        }

        is MoaResult.Failed -> copy(config = Loadable.Failed(msg.error, msg.stale), busyKey = null)

        is MoaResult.PresetsEdited -> copy(
            presets = msg.presets,
            selected = msg.selected,
            defaultPreset = msg.defaultPreset,
            activePreset = msg.activePreset,
            dirty = true,
            error = null,
            notice = null,
        )

        // 只换"看哪个预设"：不置脏（`selected` 不参与保存）。
        is MoaResult.Selected -> copy(selected = msg.name)

        is MoaResult.Invalid -> copy(error = msg.message)

        MoaResult.Saving -> copy(busyKey = "moa", error = null, notice = null)

        is MoaResult.Saved -> copy(
            busyKey = null,
            dirty = false,
            config = Loadable.Ready(msg.config),
            presets = msg.config.presets.toEditingPresets(),
            defaultPreset = msg.config.defaultPreset,
            activePreset = msg.config.activePreset,
            notice = "MoA 配置已保存（${msg.config.presets.size} 个预设）",
        )

        is MoaResult.SaveFailed -> copy(busyKey = null, error = msg.message)

        MoaResult.BannersCleared -> copy(notice = null, error = null)
    }
}
