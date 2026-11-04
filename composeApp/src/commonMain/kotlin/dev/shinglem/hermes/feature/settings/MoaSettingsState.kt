package dev.shinglem.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.shinglem.hermes.core.contract.MoaConfigDto
import dev.shinglem.hermes.core.contract.MoaConfigUpdateDto
import dev.shinglem.hermes.core.contract.MoaPresetDto
import dev.shinglem.hermes.core.contract.MoaSlotDto
import dev.shinglem.hermes.core.error.AppError
import dev.shinglem.hermes.core.error.toNotice
import dev.shinglem.hermes.core.log.Log
import dev.shinglem.hermes.core.result.Loadable
import dev.shinglem.hermes.core.result.valueOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 模型 · MoA（Mixture-of-Agents）页的状态。
 *
 * [presets] 是**整份**预设表：后端把提交的命名 map 当权威，少一个就删一个，
 * 所以编辑态必须持有全部预设，而不能只保存当前选中的那个。
 */
data class MoaSettingsState(
    val config: Loadable<MoaConfigDto> = Loadable.Idle,
    val presets: Map<String, MoaPresetDto> = emptyMap(),
    val defaultPreset: String = "default",
    /** 后端正在生效的预设；空 = 用 [defaultPreset]。 */
    val activePreset: String = "",
    val selected: String = "",
    val dirty: Boolean = false,
    val busyKey: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val preset: MoaPresetDto? get() = presets[selected]

    val presetNames: List<String> get() = presets.keys.toList()

    /** 当前预设的问题（空 = 可以保存）。 */
    val presetProblem: String? get() = preset?.let(::moaPresetProblem)
}

/** 加入 MoA 的一个槽位是否完整。 */
internal fun moaSlotProblem(slot: MoaSlotDto, label: String): String? = when {
    slot.provider.isBlank() -> "$label 缺少 provider"
    slot.model.isBlank() -> "$label 缺少模型"
    else -> null
}

/**
 * 单预设的保存前自检。后端 `validate_moa_payload` 会 422 拒绝半填预设，
 * 这里先拦一道，别让用户点了保存才看到错误。
 */
internal fun moaPresetProblem(preset: MoaPresetDto): String? {
    preset.referenceModels.forEachIndexed { index, slot ->
        moaSlotProblem(slot, "参考模型 #${index + 1}")?.let { return it }
    }
    if (preset.referenceModels.isEmpty()) return "至少需要一个参考模型"
    moaSlotProblem(preset.aggregator, "聚合模型")?.let { return it }
    return null
}

/** 整份预设表的问题（保存前查全部，不只是当前选中那个）。 */
internal fun moaConfigProblem(presets: Map<String, MoaPresetDto>): String? {
    if (presets.isEmpty()) return "至少要有一个预设"
    presets.forEach { (name, preset) ->
        moaPresetProblem(preset)?.let { return "预设「$name」：$it" }
    }
    return null
}

/** fan-out 节奏的可选项（`every_n:N` 由 UI 单独生成）。 */
val MOA_FANOUTS: List<Pair<String, String>> = listOf(
    "user_turn" to "每个用户回合一次（默认，最省）",
    "per_iteration" to "每次工具迭代（最贵）",
)

/** 失败顾问的披露策略。 */
val MOA_POLICIES: List<Pair<String, String>> = listOf(
    "loud" to "明确说明哪个顾问失败",
    "silent" to "静默降级",
)

/**
 * MoA 页（`/api/model/moa`）。
 *
 * 只操作命名预设形状；后端返回的扁平镜像（默认预设的副本）不参与编辑，
 * 免得两份数据互相覆盖。`privacy_filter` 由后端维护，这里不提交。
 */
class MoaViewModel(
    private val admin: ModelAdminRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MoaSettingsState())
    val state: StateFlow<MoaSettingsState> = _state.asStateFlow()

    private var profile: String? = null
    private var loaded = false

    fun setProfile(next: String?) {
        if (next == profile) return
        profile = next
        loaded = false
        _state.update { MoaSettingsState() }
    }

    fun onVisible() {
        if (!loaded) refresh()
    }

    fun refresh() {
        loaded = true
        _state.update {
            it.copy(config = if (it.config.valueOrNull == null) Loadable.Loading else it.config)
        }
        viewModelScope.launch {
            try {
                val moa = admin.moa(profile)
                _state.update { state ->
                    val selected = state.selected.takeIf { it in moa.presets }
                        ?: moa.activePreset.takeIf { it in moa.presets }
                        ?: moa.defaultPreset.takeIf { it in moa.presets }
                        ?: moa.presets.keys.firstOrNull().orEmpty()
                    state.copy(
                        config = Loadable.Ready(moa),
                        presets = moa.presets,
                        defaultPreset = moa.defaultPreset,
                        activePreset = moa.activePreset,
                        selected = selected,
                        dirty = false,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(config = Loadable.Failed(throwable.asMoaError())) }
            }
        }
    }

    fun select(name: String) {
        _state.update { if (name in it.presets) it.copy(selected = name, error = null) else it }
    }

    /** 以当前选中的预设为模板新建一个（名字冲突时自动加序号）。 */
    fun addPreset() {
        val state = _state.value
        val base = state.preset
        if (base == null) {
            _state.update { it.copy(error = "先等到配置读取完成") }
            return
        }
        var index = 1
        var name = "preset-$index"
        while (name in state.presets) {
            index++
            name = "preset-$index"
        }
        _state.update {
            it.copy(
                presets = it.presets + (name to base),
                selected = name,
                dirty = true,
                error = null,
                notice = null,
            )
        }
    }

    fun deletePreset() {
        val state = _state.value
        val name = state.selected
        if (name !in state.presets) return
        if (state.presets.size <= 1) {
            _state.update { it.copy(error = "至少要保留一个预设") }
            return
        }
        val remaining = state.presets - name
        val nextDefault = if (state.defaultPreset == name) remaining.keys.first() else state.defaultPreset
        _state.update {
            it.copy(
                presets = remaining,
                defaultPreset = nextDefault,
                activePreset = if (it.activePreset == name) "" else it.activePreset,
                selected = remaining.keys.first(),
                dirty = true,
                error = null,
                notice = null,
            )
        }
    }

    fun setDefault() {
        val state = _state.value
        if (state.selected.isBlank()) return
        _state.update { it.copy(defaultPreset = state.selected, dirty = true, error = null) }
    }

    /** 把当前预设设为后端正在生效的那个。 */
    fun setActive() {
        val state = _state.value
        if (state.selected.isBlank()) return
        _state.update { it.copy(activePreset = state.selected, dirty = true, error = null) }
    }

    /** 整份替换当前预设（页面每次改动都给出完整预设）。 */
    fun setPreset(preset: MoaPresetDto) {
        val state = _state.value
        if (state.selected.isBlank()) return
        _state.update {
            it.copy(
                presets = it.presets + (it.selected to preset),
                dirty = true,
                error = null,
                notice = null,
            )
        }
    }

    fun addReference() {
        val state = _state.value
        val preset = state.preset ?: return
        val fallback = preset.referenceModels.lastOrNull() ?: MoaSlotDto()
        val added = preset.copy(referenceModels = preset.referenceModels + fallback)
        _state.update { it.copy(presets = it.presets + (it.selected to added), dirty = true) }
    }

    fun removeReference(index: Int) {
        val preset = _state.value.preset ?: return
        if (index !in preset.referenceModels.indices) return
        val removed = preset.copy(referenceModels = preset.referenceModels.filterIndexed { i, _ -> i != index })
        _state.update { it.copy(presets = it.presets + (it.selected to removed), dirty = true) }
    }

    fun updateReference(index: Int, slot: MoaSlotDto) {
        val preset = _state.value.preset ?: return
        if (index !in preset.referenceModels.indices) return
        val updated = preset.copy(
            referenceModels = preset.referenceModels.mapIndexed { i, old -> if (i == index) slot else old },
        )
        _state.update { it.copy(presets = it.presets + (it.selected to updated), dirty = true) }
    }

    fun save() {
        val state = _state.value
        if (state.busyKey != null) return
        moaConfigProblem(state.presets)?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update { it.copy(busyKey = "moa", error = null, notice = null) }
        viewModelScope.launch {
            try {
                val saved = admin.saveMoa(
                    profile,
                    MoaConfigUpdateDto(
                        defaultPreset = state.defaultPreset,
                        activePreset = state.activePreset,
                        presets = state.presets,
                    ),
                )
                Log.info("MoA 配置已保存：${saved.presets.keys.joinToString()}")
                _state.update {
                    it.copy(
                        busyKey = null,
                        dirty = false,
                        config = Loadable.Ready(saved),
                        presets = saved.presets,
                        defaultPreset = saved.defaultPreset,
                        activePreset = saved.activePreset,
                        notice = "MoA 配置已保存（${saved.presets.size} 个预设）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { it.copy(busyKey = null, error = "保存失败：${throwable.describeMoaError()}") }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, error = null) }
    }
}

private fun Throwable.asMoaError(): AppError =
    this as? AppError ?: AppError.Unknown(message.orEmpty())

private fun Throwable.describeMoaError(): String =
    (this as? AppError)?.toNotice() ?: (message ?: "未知错误")
