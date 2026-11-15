package dev.shinglem.hermes.component.settings.store

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import dev.shinglem.hermes.component.settings.MoaEffect
import dev.shinglem.hermes.component.settings.MoaGateway
import dev.shinglem.hermes.component.settings.MoaIntent
import dev.shinglem.hermes.component.settings.MoaResult
import dev.shinglem.hermes.component.settings.MoaState
import dev.shinglem.hermes.core.error.asAppError
import dev.shinglem.hermes.core.error.toUserNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import dev.shinglem.hermes.feature.settings.moaConfigProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * MoA 页的 IO 边界。
 *
 * 大部分 Intent 是**纯编辑**（改 presets 的某个字段），但它们的算法要读"当前选中项"，
 * 所以统一在 Executor 里算好新的整份 presets 再交给 Reducer（见 [MoaResult.PresetsEdited]
 * 的注释：Reducer 收不到外部入参）。
 */
internal class MoaExecutor(
    private val gateway: MoaGateway,
    private val profile: String?,
    executorContext: CoroutineContext,
) : CoroutineExecutor<MoaIntent, Nothing, MoaState, MoaResult, MoaEffect>(executorContext) {

    private var loadJob: Job? = null

    override fun executeIntent(intent: MoaIntent) {
        when (intent) {
            is MoaIntent.Appear -> if (intent.connected) loadIfNeeded()
            MoaIntent.Reload -> load()
            is MoaIntent.Select -> select(intent.name)
            MoaIntent.AddPreset -> addPreset()
            MoaIntent.DeletePreset -> deletePreset()
            MoaIntent.SetDefault -> edit { it.copy(defaultPreset = it.selected) }
            MoaIntent.SetActive -> edit { it.copy(activePreset = it.selected) }
            is MoaIntent.SetPreset -> edit { state ->
                if (state.selected.isBlank()) state
                else state.copy(presets = state.presets.putting(state.selected, intent.preset))
            }

            MoaIntent.AddReference -> edit { state ->
                val preset = state.presets[state.selected] ?: return@edit state
                val fallback = preset.referenceModels.lastOrNull()
                    ?: dev.shinglem.hermes.core.contract.MoaSlotDto()
                state.copy(
                    presets = state.presets.putting(
                        state.selected,
                        preset.copy(referenceModels = preset.referenceModels + fallback),
                    ),
                )
            }

            is MoaIntent.RemoveReference -> edit { state ->
                val preset = state.presets[state.selected] ?: return@edit state
                if (intent.index !in preset.referenceModels.indices) return@edit state
                state.copy(
                    presets = state.presets.putting(
                        state.selected,
                        preset.copy(
                            referenceModels = preset.referenceModels
                                .filterIndexed { i, _ -> i != intent.index },
                        ),
                    ),
                )
            }

            is MoaIntent.UpdateReference -> edit { state ->
                val preset = state.presets[state.selected] ?: return@edit state
                if (intent.index !in preset.referenceModels.indices) return@edit state
                state.copy(
                    presets = state.presets.putting(
                        state.selected,
                        preset.copy(
                            referenceModels = preset.referenceModels
                                .mapIndexed { i, old -> if (i == intent.index) intent.slot else old },
                        ),
                    ),
                )
            }

            MoaIntent.Save -> save()
            MoaIntent.DismissBanner -> dispatch(MoaResult.BannersCleared)
        }
    }

    /** 把"改一份 state"的动作收敛到一处，统一发 [MoaResult.PresetsEdited]。 */
    private inline fun edit(block: (MoaState) -> MoaState) {
        val before = state()
        val after = block(before)
        // 什么都没变（例如选中的预设不存在）：不发结果，免得把 dirty 误标成 true。
        if (after === before) return
        dispatch(
            MoaResult.PresetsEdited(
                presets = after.presets,
                selected = after.selected,
                defaultPreset = after.defaultPreset,
                activePreset = after.activePreset,
            ),
        )
    }

    private fun loadIfNeeded() {
        val state = state()
        if (state.profile == profile && state.config !is Loadable.Idle) return
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val stale = state().config.valueOrNull
        dispatch(MoaResult.Loading(stale))
        loadJob = scope.launch {
            try {
                dispatch(MoaResult.Loaded(gateway.moa(profile), profile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(MoaResult.Failed(throwable.asAppError(), stale))
            }
        }
    }

    private fun select(name: String) {
        if (name !in state().presets) return
        // 只改"看哪个"：`selected` 不参与保存，置脏会让用户以为改坏了配置。
        dispatch(MoaResult.Selected(name))
    }

    private fun addPreset() {
        val state = state()
        val base = state.presets[state.selected]
        if (base == null) {
            dispatch(MoaResult.Invalid("先等到配置读取完成"))
            return
        }
        var index = 1
        var name = "preset-$index"
        while (name in state.presets) {
            index++
            name = "preset-$index"
        }
        edit { it.copy(presets = it.presets.putting(name, base), selected = name) }
    }

    private fun deletePreset() {
        val state = state()
        val name = state.selected
        if (name !in state.presets) return
        if (state.presets.size <= 1) {
            dispatch(MoaResult.Invalid("至少要保留一个预设"))
            return
        }
        val remaining = state.presets.removing(name)
        edit {
            it.copy(
                presets = remaining,
                // 删的正好是默认/生效预设时要一起改，否则保存后后端会指向不存在的名字。
                defaultPreset = if (it.defaultPreset == name) {
                    remaining.keys.first()
                } else {
                    it.defaultPreset
                },
                activePreset = if (it.activePreset == name) "" else it.activePreset,
                selected = remaining.keys.first(),
            )
        }
    }

    private fun save() {
        val state = state()
        if (state.busyKey != null) return
        moaConfigProblem(state.presets)?.let { problem ->
            dispatch(MoaResult.Invalid(problem))
            return
        }
        dispatch(MoaResult.Saving)
        scope.launch {
            try {
                val saved = gateway.saveMoa(
                    profile,
                    dev.shinglem.hermes.core.contract.MoaConfigUpdateDto(
                        defaultPreset = state.defaultPreset,
                        activePreset = state.activePreset,
                        presets = state.presets,
                    ),
                )
                Log.info("MoA 配置已保存：${saved.presets.keys.joinToString()}")
                dispatch(MoaResult.Saved(saved))
                publish(MoaEffect.Toast("MoA 配置已保存"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                dispatch(MoaResult.SaveFailed("保存失败：${throwable.toUserNotice()}"))
            }
        }
    }
}
